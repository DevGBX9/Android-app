"""محاكاة محلية لسلسلة النشر: Firebase وR2 وGraph API وهمية، بلا شبكة.

تشغيل: python backend/src/_smoke.py (ثم يُحذف الملف).
"""

import asyncio
import importlib.util
import json
import sys
import types
import time

# ---- وحدة workers الوهمية -------------------------------------------------


class Response:
    def __init__(self, body="", status=200, headers=None):
        self._body = body
        self.status = status
        self.headers = headers or {}

    async def text(self):
        return self._body

    async def bytes(self):
        return self._body.encode()

    async def json(self):
        return json.loads(self._body)


STATE = {
    "db": {},
    "etags": {},
    "log": [],
    "objects": {},
    "ig_media": {},
    "ig_published": [],
}


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
        node = node.setdefault(part, {})
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
        STATE["publishes"] = STATE.get("publishes", 0) + 1
        if STATE.get("slow_publish"):
            await asyncio.sleep(0.4)
        STATE["ig_media"]["media-1"] = True
        return Response('{"id":"media-1"}')
    if target == "container-1":
        return Response('{"status_code":"FINISHED","status":"ok"}')
    if target in STATE["ig_media"]:
        if STATE.get("verify_ok") is False:
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
        return None

    async def delete(self, key):
        STATE["objects"].pop(key, None)


class Env:
    FIREBASE_DB = "http://fake-db"
    IG_USER_ID = "1784"
    IG_ACCESS_TOKEN = "token"
    PUBLIC_BASE_URL = "https://example.test"
    FILES = Files()


async def main():
    spec = importlib.util.spec_from_file_location("pigeon_entry", "backend/src/entry.py")
    entry = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(entry)

    worker = entry.Default()
    worker.env = Env()

    now = int(time.time() * 1000)
    reel_id = "reel-1"
    STATE["db"]["reels"] = {
        reel_id: {"url": "https://www.tiktok.com/@x/video/123456", "title": "t"}
    }
    STATE["db"]["schedules"] = {reel_id: {"at": now - 1000, "timezone": "Africa/Algiers"}}
    STATE["objects"]["reels/%s.mp4" % reel_id] = b"video-bytes"

    seen = []
    for run in range(8):
        report = await worker._publish_due()
        states = [(r["state"], r.get("error")) for r in report["results"]]
        print("run", run, "due", report["due"], states)
        if states:
            seen.append(states[0][0])
        if not STATE["db"].get("schedules") and not STATE["db"].get("reels"):
            print("records cleared after run", run)
            break

    writes = [
        item[3] for item in STATE["log"] if item[0] == "write" and item[1] == "schedules/" + reel_id
    ]
    print("stage writes:", writes)
    print("reels left:", STATE["db"].get("reels"))
    print("schedules left:", STATE["db"].get("schedules"))
    print("objects left:", STATE["objects"])
    print("deletes:", [i for i in STATE["log"] if i[0] == "delete"])

    expected = [
        "preparing",
        "prepared",
        "publishing",
        "verifying",
        "cleaning",
    ]
    for stage in expected:
        assert stage in writes, "missing stage " + stage
    assert writes.index("preparing") < writes.index("prepared") < writes.index("publishing")
    assert writes.index("publishing") < writes.index("verifying") < writes.index("cleaning")
    assert not STATE["objects"], "the archived file must be deleted"
    assert not STATE["db"].get("reels") and not STATE["db"].get("schedules")
    assert not [i for i in STATE["log"] if i[0] == "conflict"], "unexpected CAS conflict"

    # حذف من التطبيق: ملف + سجلات
    STATE["db"]["reels"] = {"reel-2": {"url": "https://instagram.com/reel/abc"}}
    STATE["db"]["schedules"] = {"reel-2": {"at": now + 10**6, "state": "prepared"}}
    STATE["objects"]["reels/reel-2.mp4"] = b"x"
    result = await worker._delete_reel("reel-2")
    print("delete status:", result.status, result._body)
    assert result.status == 200
    assert not STATE["objects"] and not STATE["db"].get("reels") and not STATE["db"].get("schedules")

    # حذف مرفوض أثناء النشر
    STATE["db"]["schedules"] = {"reel-3": {"at": now, "state": "publishing"}}
    STATE["objects"]["reels/reel-3.mp4"] = b"x"
    blocked = await worker._delete_reel("reel-3")
    print("delete while publishing:", blocked.status)
    assert blocked.status == 409

    # تشغيلان متزامنان لا ينشران الريل مرتين (حجز ETag).
    STATE["db"]["reels"] = {"reel-4": {"url": "https://instagram.com/reel/xyz"}}
    STATE["db"]["schedules"] = {
        "reel-4": {
            "at": now - 1000,
            "state": "prepared",
            "containerId": "container-1",
        }
    }
    STATE["objects"]["reels/reel-4.mp4"] = b"x"
    STATE["publishes"] = 0
    STATE["slow_publish"] = True
    await asyncio.gather(worker._publish_due(), worker._publish_due())
    print("media_publish calls for concurrent runs:", STATE["publishes"])
    assert STATE["publishes"] == 1, "the same reel was published twice"

    # إنستغرام لا يؤكد النشر: الملف والسجلات تبقى (لا حذف أعمى).
    STATE["verify_ok"] = False
    STATE["db"]["reels"] = {"reel-5": {"url": "https://instagram.com/reel/q"}}
    STATE["db"]["schedules"] = {
        "reel-5": {
            "at": now - 1000,
            "state": "verifying",
            "mediaId": "media-1",
            "publishedAt": now - 1000,
            "cleanupAt": now - 500,
        }
    }
    STATE["objects"]["reels/reel-5.mp4"] = b"keep-me"
    for _ in range(entry.VERIFY_RETRY_LIMIT + 2):
        await worker._publish_due()
    final = STATE["db"].get("schedules", {}).get("reel-5", {})
    print("unverified state:", final.get("state"), "| file kept:", "reels/reel-5.mp4" in STATE["objects"])
    assert final.get("state") == "unverified"
    assert "reels/reel-5.mp4" in STATE["objects"], "the file was deleted without confirmation"
    assert "reel-5" in STATE["db"].get("reels", {})

    print("SMOKE OK")


asyncio.run(main())
