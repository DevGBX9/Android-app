"""محاكاة محلية للـWorker: Firebase وR2 وGraph API وهمية، بلا شبكة.

تُشغَّل من جذر المستودع:  python docs/tools/publish_smoke.py

تتحقق من العقود التي يقوم عليها النظام:
- سلسلة النشر بالترتيب: preparing → prepared → publishing → verifying → cleaning.
- حجز الخطوة بـETag: تشغيلان متزامنان لا ينشران الريل مرتين.
- لا حذف بلا تأكيد من Graph (يبقى الملف والسجلات).
- idempotency: نشر الآن مرتين ينشر مرة، والحذف مرتين ينجح مرتين.
- النشر اليدوي يعيد الريل الفاشل إلى الطابور.
- إعادة ترتيب الشبكة تتبادل المواعيد ولا تنقل موعداً فوق موعد ريل آخر.
- /state هو مصدر الحقيقة: الأوامر المسموحة تُشتق من الحالة وحدها.
"""

import asyncio
import importlib.util
import json
import sys
import time
import types

# ---- وحدة workers الوهمية -------------------------------------------------


class Response:
    def __init__(self, body="", status=200, headers=None):
        self._body = body
        self.status = status
        self.headers = headers or {}

    async def text(self):
        return self._body

    async def bytes(self):
        return self._body.encode() if isinstance(self._body, str) else self._body

    async def json(self):
        return json.loads(self._body)


STATE = {
    "db": {},
    "etags": {},
    "log": [],
    "objects": {},
    "ig_media": {},
    "ig_published": [],
    "publishes": 0,
    "slow_publish": False,
    "verify_ok": True,
    "fail_publish": False,
}


def reset():
    STATE["db"] = {}
    STATE["etags"] = {}
    STATE["log"] = []
    STATE["objects"] = {}
    STATE["ig_media"] = {}
    STATE["ig_published"] = []
    STATE["publishes"] = 0
    STATE["slow_publish"] = False
    STATE["verify_ok"] = True
    STATE["fail_publish"] = False


def _fb_parts(url):
    path = url.split("//", 1)[1].split("/", 1)[1]
    path = path.split("?", 1)[0]
    if path.endswith(".json"):
        path = path[:-5]
    return [p for p in path.split("/") if p]


def _fb_get_node(parts):
    node = STATE["db"]
    for part in parts:
        if not isinstance(node, dict) or part not in node:
            return None
        node = node[part]
    return node


def _fb_set_node(parts, value):
    node = STATE["db"]
    for part in parts[:-1]:
        # الحذف لا يُنشئ عُقداً وسيطة (سلوك Firebase الفعلي).
        if not isinstance(node.get(part), dict):
            if value is None:
                return
            node[part] = {}
        node = node[part]
    if value is None:
        node.pop(parts[-1], None)
    else:
        node[parts[-1]] = value


def _touch(key):
    STATE["etags"][key] = STATE["etags"].get(key, 0) + 1
    return '"%d"' % STATE["etags"][key]


async def fetch(url, method="GET", headers=None, body=None, redirect="follow", timeout=None):
    headers = {k.lower(): v for k, v in (headers or {}).items()}
    if url.startswith("https://graph.facebook.com/"):
        return await _graph(url, method)
    if url.startswith("http://fake-db/"):
        return _firebase(url, method, headers, body)
    raise RuntimeError("unexpected url %s" % url)


def _firebase(url, method, headers, body):
    parts = _fb_parts(url)
    key = "/".join(parts)

    if method == "GET":
        node = _fb_get_node(parts)
        etag = STATE["etags"].get(key, 0)
        return Response(
            json.dumps(node) if node is not None else "null",
            headers={"etag": '"%d"' % etag},
        )

    # Firebase RTDB الحقيقي يرفض if-match على PATCH ويقبلها على PUT.
    if headers.get("if-match") and method not in ("PUT", "DELETE"):
        STATE["log"].append(("if-match-unsupported", key, method))
        return Response('{"error":"if-match not supported"}', status=400)

    if headers.get("if-match"):
        expected = headers["if-match"].strip('"')
        if expected != str(STATE["etags"].get(key, 0)):
            STATE["log"].append(("conflict", key, method))
            return Response('{"error":"precondition"}', status=412)

    if method in ("PUT", "PATCH"):
        payload = json.loads(body)
        if method == "PUT":
            _fb_set_node(parts, payload)
        else:
            node = _fb_get_node(parts)
            merged = dict(node) if isinstance(node, dict) else {}
            merged.update(payload)
            _fb_set_node(parts, merged)
        _touch(key)
        STATE["log"].append(("write", key, method, payload.get("state")))
        return Response(body)

    if method == "DELETE":
        _fb_set_node(parts, None)
        _touch(key)
        STATE["log"].append(("delete", key))
        return Response("null")

    return Response("{}", status=405)


async def _graph(url, method):
    path = url.split("graph.facebook.com/v23.0/", 1)[1]
    target = path.split("?", 1)[0]

    if method == "POST" and target.endswith("/media"):
        return Response('{"id":"container-1"}')
    if method == "POST" and target.endswith("/media_publish"):
        if STATE["fail_publish"]:
            return Response('{"error":{"message":"boom"}}', status=400)
        STATE["publishes"] += 1
        if STATE["slow_publish"]:
            await asyncio.sleep(0.05)
        STATE["ig_media"]["media-1"] = True
        return Response('{"id":"media-1"}')
    if target == "container-1":
        return Response('{"status_code":"FINISHED","status":"ok"}')
    if target in STATE["ig_media"]:
        if STATE["verify_ok"] is False:
            return Response('{"error":{"message":"missing"}}', status=400)
        return Response(json.dumps({"id": target, "media_type": "VIDEO"}))
    if target.endswith("/media"):
        return Response(json.dumps({"data": STATE["ig_published"]}))
    return Response('{"error":{"message":"missing"}}', status=400)


class WorkerEntrypoint:
    def __init__(self, *args, **kwargs):
        pass


workers = types.ModuleType("workers")
workers.Response = Response
workers.WorkerEntrypoint = WorkerEntrypoint
workers.fetch = fetch
sys.modules["workers"] = workers


class Files:
    async def put(self, key, data):
        STATE["objects"][key] = data

    async def head(self, key):
        return object() if key in STATE["objects"] else None

    async def get(self, key):
        if key not in STATE["objects"]:
            return None
        return type(
            "Object",
            (),
            {"body": STATE["objects"][key], "size": len(STATE["objects"][key])},
        )()

    async def delete(self, key):
        STATE["objects"].pop(key, None)


class Env:
    FIREBASE_DB = "http://fake-db"
    IG_USER_ID = "1784"
    IG_ACCESS_TOKEN = "token"
    PUBLIC_BASE_URL = "https://example.test"
    FILES = Files()


class FakeRequest:
    def __init__(self, payload):
        self._payload = payload

    async def json(self):
        return self._payload


def load_entry():
    spec = importlib.util.spec_from_file_location("pigeon_entry", "backend/src/entry.py")
    entry = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(entry)
    return entry


def stage_writes(reel_id):
    return [
        item[3]
        for item in STATE["log"]
        if item[0] == "write" and item[1] == "schedules/" + reel_id
    ]


async def scenario_chain(entry, worker):
    """السلسلة كاملة: ترتيب الخطوات، ثم حذف الملف والسجلات."""
    reset()
    now = int(time.time() * 1000)
    reel_id = "reel-1"
    STATE["db"]["reels"] = {"reel-1": {"url": "https://www.instagram.com/reel/abc"}}
    STATE["db"]["schedules"] = {reel_id: {"at": now - 5000, "timezone": "Africa/Algiers"}}
    STATE["objects"]["reels/%s.mp4" % reel_id] = b"video-bytes"

    for _run in range(8):
        await worker._publish_due()
        if not STATE["db"].get("schedules") and not STATE["db"].get("reels"):
            break

    writes = stage_writes(reel_id)
    expected = ["preparing", "prepared", "publishing", "verifying", "cleaning"]
    for stage in expected:
        assert stage in writes, "missing stage " + stage
    order = [writes.index(stage) for stage in expected]
    assert order == sorted(order), "stages ran out of order: %s" % writes
    assert not STATE["objects"], "the archived file must be deleted after publishing"
    assert not STATE["db"].get("reels") and not STATE["db"].get("schedules")
    assert not [i for i in STATE["log"] if i[0] == "conflict"], "unexpected CAS conflict"
    print("chain: ok", writes)


async def scenario_concurrent(entry, worker):
    """تشغيلان متزامنان: media_publish مرة واحدة فقط."""
    reset()
    now = int(time.time() * 1000)
    STATE["db"]["reels"] = {"reel-4": {"url": "https://www.instagram.com/reel/xyz"}}
    STATE["db"]["schedules"] = {
        "reel-4": {"at": now - 5000, "state": "prepared", "containerId": "container-1"}
    }
    STATE["objects"]["reels/reel-4.mp4"] = b"x"
    STATE["slow_publish"] = True

    await asyncio.gather(worker._publish_due(), worker._publish_due())
    print("media_publish calls for concurrent runs:", STATE["publishes"])
    assert STATE["publishes"] == 1, "the same reel was published twice"


async def scenario_unverified(entry, worker):
    """نشر لم يؤكده Graph: لا حذف أعمى."""
    reset()
    now = int(time.time() * 1000)
    STATE["db"]["reels"] = {"reel-5": {"url": "https://www.instagram.com/reel/q"}}
    STATE["db"]["schedules"] = {
        "reel-5": {
            "at": now - 5000,
            "state": "verifying",
            "mediaId": "media-1",
            "publishedAt": now - 5000,
            "cleanupAt": now - 4500,
        }
    }
    STATE["objects"]["reels/reel-5.mp4"] = b"keep-me"
    STATE["verify_ok"] = False

    for _ in range(entry.VERIFY_RETRY_LIMIT + 2):
        await worker._publish_due()

    final = STATE["db"].get("schedules", {}).get("reel-5", {})
    print("unverified state:", final.get("state"), "| file kept:", "reels/reel-5.mp4" in STATE["objects"])
    assert final.get("state") == "unverified"
    assert "reels/reel-5.mp4" in STATE["objects"], "the file was deleted without confirmation"
    assert "reel-5" in STATE["db"].get("reels", {})


async def scenario_publish_now_idempotent(entry, worker):
    """انشر الآن: ينشر ويتنظّف في الطلب، والتكرار لا ينشر ثانية."""
    reset()
    now = int(time.time() * 1000)
    reel_id = "reel-6"
    STATE["db"]["reels"] = {reel_id: {"url": "https://www.instagram.com/reel/now"}}
    STATE["db"]["schedules"] = {
        reel_id: {"at": now + 10 * 24 * 3600 * 1000, "state": "scheduled"}
    }
    STATE["objects"]["reels/%s.mp4" % reel_id] = b"now-bytes"

    payload, status = await worker.commands.publish_now(reel_id)
    print("publish-now:", status, payload)
    assert status == 200 and payload["published"] is True
    assert STATE["publishes"] == 1
    assert not STATE["objects"]

    # التكرار بعد النشر: لا نشر ثانٍ، ولا خطأ.
    STATE["db"]["schedules"] = {reel_id: {"at": now, "state": "published"}}
    payload2, status2 = await worker.commands.publish_now(reel_id)
    print("publish-now twice:", status2, payload2)
    assert payload2["published"] is True and payload2["idempotent"] is True
    assert STATE["publishes"] == 1, "publish-now published the same reel twice"

    # أثناء النشر: الأمر يعيد in_progress بلا بدء سلسلة ثانية.
    STATE["db"]["schedules"] = {reel_id: {"at": now, "state": "publishing"}}
    payload3, status3 = await worker.commands.publish_now(reel_id)
    assert status3 == 202 and payload3["errorCode"] == "in_progress"
    print("publish-now while publishing: 202 in_progress")


async def scenario_failed_retry(entry, worker):
    """النشر اليدوي يعيد الريل الفاشل إلى الطابور بدل تركه ميتاً."""
    reset()
    now = int(time.time() * 1000)
    reel_id = "reel-7"
    STATE["db"]["reels"] = {reel_id: {"url": "https://www.instagram.com/reel/retry"}}
    STATE["db"]["schedules"] = {
        reel_id: {"at": now - 5000, "state": "failed", "error": "old failure"}
    }
    STATE["objects"]["reels/%s.mp4" % reel_id] = b"retry-bytes"

    payload, status = await worker.commands.publish_now(reel_id)
    print("publish-now after failure:", status, payload.get("state"))
    assert payload["published"] is True
    assert STATE["publishes"] == 1


async def scenario_delete(entry, worker):
    """الحذف: مرة تنجح، والتكرار ينجح بلا أثر، وأثناء النشر يُرفض."""
    reset()
    now = int(time.time() * 1000)
    STATE["db"]["reels"] = {"reel-2": {"url": "https://instagram.com/reel/abc"}}
    STATE["db"]["schedules"] = {"reel-2": {"at": now + 10**6, "state": "prepared"}}
    STATE["objects"]["reels/reel-2.mp4"] = b"x"

    payload, status = await worker.commands.delete("reel-2")
    assert status == 200 and payload["deleted"] is True
    assert not STATE["objects"] and not STATE["db"].get("reels") and not STATE["db"].get("schedules")

    # الحذف مرتين: لا شيء متبقٍ، والنتيجة نفسها.
    payload, status = await worker.commands.delete("reel-2")
    assert status == 200 and payload["deleted"] is True
    print("delete twice: ok")

    STATE["db"]["schedules"] = {"reel-3": {"at": now, "state": "publishing"}}
    STATE["objects"]["reels/reel-3.mp4"] = b"x"
    try:
        await worker.commands.delete("reel-3")
        raise AssertionError("delete during publishing must be refused")
    except entry.ApiError as exc:
        print("delete while publishing:", exc.status, exc.code)
        assert exc.status == 409


async def scenario_schedule(entry, worker):
    """الجدولة: تدقيق الموعد، منع التداخل، والتكرار بلا كتابة."""
    reset()
    now = int(time.time() * 1000)
    STATE["db"]["reels"] = {
        "reel-a": {"url": "https://www.instagram.com/reel/a"},
        "reel-b": {"url": "https://www.instagram.com/reel/b"},
    }
    base = now + 3 * 24 * 3600 * 1000

    payload, status = await worker.commands.set_schedule(
        {"id": "reel-a", "at": base + 12_345}
    )
    assert status == 200 and payload["at"] % 60_000 == 0, "the slot must snap to a minute"
    first_at = payload["at"]
    print("schedule:", first_at, "shifted:", payload["shifted"])

    # نفس الأمر مرة أخرى: نتيجة نفسها بلا كتابة جديدة.
    writes_before = len(STATE["log"])
    payload, status = await worker.commands.set_schedule(
        {"id": "reel-a", "at": first_at}
    )
    assert payload["idempotent"] is True
    assert len(STATE["log"]) == writes_before, "an idempotent schedule must not write"
    print("schedule idempotent: ok")

    # موعد متداخل مع ريل آخر يُزاح بفاصل كافٍ.
    payload, status = await worker.commands.set_schedule(
        {"id": "reel-b", "at": first_at + 60_000}
    )
    assert payload["at"] - first_at >= entry.MIN_SLOT_GAP_MS
    print("schedule shift:", payload["at"] - first_at, "ms")

    # موعد في الماضي يُرفض قبل أي كتابة.
    try:
        await worker.commands.set_schedule({"id": "reel-a", "at": now - 5 * 60 * 1000})
        raise AssertionError("a past time must be refused")
    except entry.ApiError as exc:
        assert exc.code == "invalid_request"


async def scenario_reorder(entry, worker):
    """الترتيب: المواعيد تتبادل، ولا تنتقل فوق موعد ريل غير مشمول."""
    reset()
    now = int(time.time() * 1000)
    day = 24 * 3600 * 1000
    base = now + 40 * day
    STATE["db"]["reels"] = {
        "reel-a": {"url": "https://instagram.com/reel/a"},
        "reel-b": {"url": "https://instagram.com/reel/b"},
        "reel-c": {"url": "https://instagram.com/reel/c"},
    }
    STATE["db"]["schedules"] = {
        "reel-a": {"at": base, "state": "scheduled"},
        "reel-b": {"at": base + day, "state": "prepared", "containerId": "container-1"},
        "reel-c": {"at": base + 2 * day, "state": "scheduled"},
    }

    payload, status = await worker.commands.reorder(
        "req-1", {"ids": ["reel-c", "reel-a", "reel-b"]}
    )
    assert status == 200, payload
    schedules = STATE["db"]["schedules"]
    assert schedules["reel-c"]["at"] == base
    assert schedules["reel-a"]["at"] == base + day
    assert schedules["reel-b"]["at"] == base + 2 * day
    # الحاوية القديمة أُلغيت والحالة رجعت scheduled لتُحضّر عند الموعد الجديد.
    assert schedules["reel-b"]["state"] == "scheduled"
    assert not schedules["reel-b"].get("containerId")
    print("reorder: ok", payload["moved"], "moved")

    # ريل يُنشر الآن: لا سحب.
    STATE["db"]["schedules"]["reel-a"]["state"] = "publishing"
    try:
        await worker.commands.reorder("req-2", {"ids": ["reel-a", "reel-c"]})
        raise AssertionError("reorder during publishing must be refused")
    except entry.ApiError as exc:
        assert exc.status == 409
        print("reorder while publishing:", exc.code)


async def scenario_state(entry, worker):
    """اللقطة: الأوامر المسموحة تُشتق من الحالة، والساعة من الخادم."""
    reset()
    now = int(time.time() * 1000)
    STATE["db"]["reels"] = {
        "reel-a": {
            "url": "https://instagram.com/reel/a",
            "archive": {"state": "ready", "progress": 100, "bytes": 10},
        },
        "reel-b": {"url": "https://instagram.com/reel/b", "archive": {"state": "downloading", "progress": 40}},
    }
    STATE["db"]["schedules"] = {
        "reel-a": {"at": now + 10**6, "state": "scheduled", "updatedAt": now},
        "reel-b": {"at": now + 2 * 10**6, "state": "verifying"},
    }

    snapshot = await entry._build_state(worker.store, worker.env)
    assert snapshot["ok"] and snapshot["serverTime"] > 0
    assert snapshot["reels"]["reel-a"]["downloadable"] is True
    assert snapshot["reels"]["reel-b"]["downloadable"] is False
    assert snapshot["reels"]["reel-b"]["allowedActions"] == []
    assert snapshot["publishing"]["id"] == "reel-b"
    assert snapshot["reels"]["reel-a"]["order"] == 1
    print("state: ok", snapshot["reels"]["reel-a"]["allowedActions"])

    # الحالة غير المؤكدة: الحذف فقط.
    view = entry._reel_view({"at": now, "state": "unverified"}, {}, now)
    assert view["allowedActions"] == ["delete"]
    print("state unverified actions:", view["allowedActions"])


async def scenario_archive_idempotent(entry, worker):
    """طلب أرشفة لريل مؤرشف: ready بلا إعادة تنزيل ولا شبكة."""
    reset()
    STATE["db"]["reels"] = {
        "reel-z": {
            "url": "https://www.instagram.com/reel/z",
            "archive": {"state": "ready", "progress": 100, "bytes": 99, "url": "u"},
        }
    }
    payload, status = await worker.commands.archive(
        "req-a", {"id": "reel-z", "url": "https://www.instagram.com/reel/z"}
    )
    assert status == 200 and payload["state"] == "ready"
    print("archive idempotent:", payload["state"])

    # مهمة جارية حديثاً: لا تُصفَّر نسبتها.
    STATE["db"]["reels"]["reel-y"] = {
        "url": "https://www.instagram.com/reel/y",
        "archive": {"state": "downloading", "progress": 61, "updatedAt": int(time.time() * 1000)},
    }
    payload, status = await worker.commands.archive(
        "req-b", {"id": "reel-y", "url": "https://www.instagram.com/reel/y"}
    )
    assert status == 202 and payload["archive"]["progress"] == 61
    print("archive in-flight kept progress:", payload["archive"]["progress"])


async def scenario_cas(entry, worker):
    """الكتابة المشروطة: ETag قديم يفشل ثم ينجح بعد إعادة القراءة."""
    reset()
    store = worker.store
    await store.patch("schedules/cas.json", {"at": 1, "state": "scheduled"})
    stale_record, stale_etag = await store.read("schedules/cas.json")

    # كتابة من جهة أخرى تُغيّر السجل.
    fresh, fresh_etag = await store.read("schedules/cas.json")
    await store.commit("schedules/cas.json", fresh_etag, fresh, {"at": 2})

    merged = await store.commit(
        "schedules/cas.json", stale_etag, stale_record, {"error": "x"}
    )
    assert merged["at"] == 2, "a stale write must not clobber newer fields"
    assert merged["error"] == "x"
    print("cas: stale write retried, newer field kept")


async def main():
    entry = load_entry()
    # تسريع الانتظار في المحاكاة فقط (لا يغيّر منطق الإنتاج).
    entry.CLEANUP_DELAY_MS = 0
    entry.PUBLISH_NOW_POLL_S = 0

    worker = entry.Default()
    worker.env = Env()

    await scenario_chain(entry, worker)
    await scenario_concurrent(entry, worker)
    await scenario_unverified(entry, worker)
    await scenario_publish_now_idempotent(entry, worker)
    await scenario_failed_retry(entry, worker)
    await scenario_delete(entry, worker)
    await scenario_schedule(entry, worker)
    await scenario_reorder(entry, worker)
    await scenario_state(entry, worker)
    await scenario_archive_idempotent(entry, worker)
    await scenario_cas(entry, worker)

    print("SMOKE OK")


asyncio.run(main())
