// ═══════════════════════════════════════════════════════════
//  بيانات حساب إنستغرام لايف — عبر بروكسي Cloudflare Worker (التوكن سيرفر-سايد)
// ═══════════════════════════════════════════════════════════
const CONFIG = {
  // بروكسي Cloudflare Worker — التوكن مخزّن كسر هناك، ما بيوصل للمتصفح
  apiEndpoint: "https://brainrot-pigeon-api.brainrotpigeon.workers.dev/",

  // كل كم نحدّث (بالميلي ثانية)
  refreshMs: 30 * 1000, // 30 ثانية

  // أقل فرق بين أي طلبين (حتى لو صارت حركة من المستخدم)
  minRequestGapMs: 20 * 1000,

  // مهلة الطلب/تحميل الصورة قبل ما نعتبرها فاشلة
  timeoutMs: 9000,
};

// بيانات احتياطية تظهر فوراً قبل ما يرجع الرد (أو لو النت مقطوع)
const FALLBACK = {
  username: "brainrot.pigeon",
  bio: "I'm a pigeon",
  posts: 48,
  followers: 3099,
  following: 5,
  avatar: "",
};

const LOCAL_AVATAR = "assets/avatar.webp";
const CACHE_KEY = "brainrot.pigeon:profile:v2";

const els = {
  username: document.getElementById("username"),
  posts: document.getElementById("posts"),
  followers: document.getElementById("followers"),
  following: document.getElementById("following"),
  bio: document.getElementById("bio"),
  note: document.getElementById("note"),
  avatar: document.getElementById("avatar"),
  avatarNext: document.getElementById("avatarNext"),
};

const note = "Can't decide...";

/* ---------- عداد بأرقام تتدحرج (odometer) ---------- */

const DIGIT_H = 1.15; // لازم تطابق .od__digit في style.css
const ROLL_MS = 720;

// شريط أرقام كل خانة: 9 (للدوران للأسفل) ثم 0..9 ثم 0 (للدوران للأعلى)
const STRIP_VALUES = [9, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0];
const offsetFor = (digit, index) => -index * DIGIT_H;

class RollDigits {
  constructor(el) {
    this.el = el;
    this.el.classList.add("od");
    this.value = null;
    this.digits = [];
  }

  _clear() {
    this.el.textContent = "";
    this.digits = [];
  }

  // رسم الرقم من الصفر (أول تحميل، أو لما يتغير عدد الخانات)
  _build(text, animateIn) {
    this._clear();
    this.el.setAttribute("aria-label", text);
    this.el.setAttribute("aria-live", "polite");

    for (const ch of text) {
      if (ch >= "0" && ch <= "9") {
        const digit = Number(ch);
        const box = document.createElement("span");
        box.className = "od__digit";
        box.setAttribute("aria-hidden", "true");

        const strip = document.createElement("span");
        strip.className = "od__strip";
        for (const v of STRIP_VALUES) {
          const cell = document.createElement("span");
          cell.textContent = v;
          strip.appendChild(cell);
        }
        strip.style.transform = `translateY(${offsetFor(digit, digit + 1)}em)`;

        box.appendChild(strip);
        this.el.appendChild(box);
        this.digits.push({ strip, digit });
      } else {
        const sep = document.createElement("span");
        sep.className = "od__sep";
        sep.textContent = ch;
        sep.setAttribute("aria-hidden", "true");
        this.el.appendChild(sep);
      }
    }

    if (animateIn) {
      this.el.classList.remove("od--pop");
      void this.el.offsetWidth;
      this.el.classList.add("od--pop");
      setTimeout(() => this.el.classList.remove("od--pop"), 400);
    }
  }

  _setText(text) {
    this.value = null;
    this._clear();
    this.el.textContent = text;
    this.el.setAttribute("aria-label", text);
  }

  set(value, animate = true) {
    if (typeof value !== "number" || !Number.isFinite(value)) {
      return this._setText("—");
    }

    const prev = this.value;
    this.value = value;
    const text = value.toLocaleString("en-US");
    this.el.setAttribute("aria-label", text);

    // أول قيمة: بدون أنيميشن
    if (prev === null || prev === value) return this._build(text, false);

    const prevText = prev.toLocaleString("en-US");
    const sameShape =
      prevText.length === text.length &&
      this.digits.length === text.replace(/\D/g, "").length;

    if (!animate || !sameShape) return this._build(text, animate);

    const up = value > prev;
    const targets = text.replace(/\D/g, "");

    this.digits.forEach((item, i) => {
      const next = Number(targets[i]);
      const current = item.digit;
      if (next === current) return;

      // للفوق: نلف عبر 9 ← 0 | للأسفل: نلف عبر 0 ← 9
      const steps = up ? (next - current + 10) % 10 : (current - next + 10) % 10;
      const toIndex = up ? current + 1 + steps : current + 1 - steps;

      item.strip.style.transform = `translateY(${offsetFor(next, toIndex)}em)`;
      item.digit = next;

      // نرجع الشريط لمكانه الطبيعي بعد الحركة بدون ما يبين
      setTimeout(() => {
        if (item.digit !== next) return;
        item.strip.style.transition = "none";
        item.strip.style.transform = `translateY(${offsetFor(next, next + 1)}em)`;
        void item.strip.offsetHeight;
        item.strip.style.transition = "";
      }, ROLL_MS + 60);
    });
  }
}

const counters = {
  posts: new RollDigits(els.posts),
  followers: new RollDigits(els.followers),
  following: new RollDigits(els.following),
};

// لو صورة فيسبوك/CDN رفضت تتحمّل، بنمرّرها عبر بروكسي صور
const proxify = (url) =>
  "https://wsrv.nl/?url=" +
  encodeURIComponent(url) +
  "&w=400&h=400&fit=cover&output=webp";

// رابط إنستغرام/فيسبوك بيتغيّر بالتوقيع (?oh=...&oe=...) مع كل طلب،
// فالمقارنة بتصير على مسار الملف نفسه — يعني "نفس الصورة" فعلاً
const avatarKey = (url) => {
  try {
    return new URL(url, location.href).pathname;
  } catch (_) {
    return url;
  }
};

let currentAvatar = LOCAL_AVATAR;
let currentAvatarKey = avatarKey(LOCAL_AVATAR);
let lastRawAvatar = "";
let pollingStopped = false;

function render(data) {
  els.username.textContent = data.username || FALLBACK.username;
  counters.posts.set(data.posts);
  counters.followers.set(data.followers);
  counters.following.set(data.following);
  els.bio.textContent = data.bio ?? FALLBACK.bio;
  els.note.textContent = note;
  document.title = "@" + (data.username || FALLBACK.username);
  if (data.avatar) {
    setAvatar(data.avatar);
    syncFavicon(data.avatar);
  }
}

/* ---------- أيقونة التبويب تتغيّر مع صورة البروفايل ---------- */

const faviconEl = document.getElementById("favicon");
const ICON_SIZE = 64;
let lastIconKey = "";

// نرسم الصورة بمقاس صغير ومقصوصة دائرية عشان تبان حلوة كأيقونة
function drawIcon(img) {
  const canvas = document.createElement("canvas");
  canvas.width = canvas.height = ICON_SIZE;
  const ctx = canvas.getContext("2d");
  const side = Math.min(img.naturalWidth, img.naturalHeight);

  ctx.save();
  ctx.beginPath();
  ctx.arc(ICON_SIZE / 2, ICON_SIZE / 2, ICON_SIZE / 2, 0, Math.PI * 2);
  ctx.clip();
  ctx.drawImage(
    img,
    (img.naturalWidth - side) / 2,
    (img.naturalHeight - side) / 2,
    side,
    side,
    0,
    0,
    ICON_SIZE,
    ICON_SIZE
  );
  ctx.restore();

  return canvas.toDataURL("image/png");
}

async function syncFavicon(url) {
  if (!faviconEl || !url) return;
  const key = avatarKey(url);
  if (key === lastIconKey) return;
  lastIconKey = key;

  try {
    const img = new Image();
    img.crossOrigin = "anonymous";
    img.referrerPolicy = "no-referrer";
    await new Promise((resolve, reject) => {
      img.onload = resolve;
      img.onerror = reject;
      img.src = url;
    });

    faviconEl.type = "image/png";
    faviconEl.href = drawIcon(img);
  } catch (_) {
    // لو المتصفح منع الرسم (canvas ملوّث) بنحط الرابط مباشرة
    faviconEl.type = "image/jpeg";
    faviconEl.href = url;
  }
}

const preloadImage = (url) =>
  new Promise((resolve) => {
    const img = new Image();
    img.referrerPolicy = "no-referrer";
    const finish = (ok) => {
      clearTimeout(timer);
      resolve(ok);
    };
    const timer = setTimeout(() => finish(false), CONFIG.timeoutMs);
    img.onload = () => finish(true);
    img.onerror = () => finish(false);
    img.src = url;
  });

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

let hasAvatar = false;
let swapping = false;
let queuedAvatar = "";

// تبديل الصورة: القديمة ترمّد (كانها عمّ تنحمّل) وبعدين الجديدة تطفو فوقها
async function setAvatar(url) {
  if (!url || url === currentAvatar) return;

  // نفس الصورة برابط موقّع جديد؟ ما في شي تغيّر
  const key = avatarKey(url);
  if (key === currentAvatarKey) return;

  lastRawAvatar = url;

  // أول صورة عند فتح الصفحة: تظهر مباشرة بدون أنيميشن
  if (!hasAvatar) {
    hasAvatar = true;
    currentAvatar = url;
    currentAvatarKey = key;
    els.avatar.src = url;
    return;
  }

  // تغييرات سريعة ورا بعض: نحفظ آخر رابط ونطبقه بعد ما تخلص الحركة
  if (swapping) {
    queuedAvatar = url;
    return;
  }

  swapping = true;
  const box = els.avatar.parentElement;

  try {
    box.classList.add("is-swapping");

    // نجهّز الصورة الجديدة + نضمن إن الرمادي يبان شوية
    const [loaded] = await Promise.all([preloadImage(url), wait(420)]);

    if (loaded) {
      els.avatarNext.src = url;
      els.avatarNext.classList.add("is-visible");
      await wait(560);

      // نحط الصورة الجديدة كطبقة أساسية، ونشيل الطبقة العائمة
      els.avatar.src = url;
      currentAvatar = url;
      currentAvatarKey = key;
      els.avatarNext.classList.remove("is-visible");
      await wait(220);
    }
  } finally {
    // مهما صار (فشل/انقطاع)، ما نسمح للتبديل يعلّق الحالة
    box.classList.remove("is-swapping");
    swapping = false;
  }

  const queued = queuedAvatar;
  queuedAvatar = "";
  if (queued && avatarKey(queued) !== currentAvatarKey) setAvatar(queued);
}

// أول شي: آخر بيانات محفوظة، وإلا الاحتياطية
function showCached() {
  try {
    const raw = localStorage.getItem(CACHE_KEY);
    if (raw) return render(JSON.parse(raw));
  } catch (_) {}
  render(FALLBACK);
}

function saveCache(data) {
  try {
    localStorage.setItem(CACHE_KEY, JSON.stringify(data));
  } catch (_) {}
}

/* ---------- السحب اللايف من Graph API ---------- */

async function fetchProfile() {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), CONFIG.timeoutMs);

  let res;
  let json;
  try {
    res = await fetch(CONFIG.apiEndpoint, {
      cache: "no-store",
      headers: { Accept: "application/json" },
      signal: controller.signal,
    });
    json = await res.json().catch(() => null);
  } finally {
    clearTimeout(timer);
  }

  if (!res.ok || !json || json.error) {
    const e = new Error((json && json.error) || `HTTP ${res.status}`);
    e.status = res.status;
    throw e;
  }

  return {
    username: json.username || FALLBACK.username,
    bio: json.bio ?? "",
    posts: json.posts,
    followers: json.followers,
    following: json.following,
    avatar: json.avatar || "",
  };
}

let syncing = false;
let lastRequestAt = 0;

async function syncProfile() {
  if (pollingStopped || syncing) return;
  syncing = true;
  lastRequestAt = Date.now();
  try {
    const data = await fetchProfile();
    render(data);
    saveCache(data);
  } catch (err) {
    // التوكن منتهي/ملغى؟ منوقفش الطلبات كل دقيقة بدون فايدة
    if (err.status === 401 || err.status === 403) {
      pollingStopped = true;
      console.warn("[brainrot.pigeon] الـAPI رفض الطلب:", err.message);
    } else {
      console.warn("[brainrot.pigeon] تعذّر تحديث البيانات:", err.message);
    }
    if (els.username.textContent === "") render(FALLBACK);
  } finally {
    syncing = false;
  }
}

// لو انكسر رابط الصورة: نجرّب البروكسي، وبعدها الصورة المحلية
els.avatar.addEventListener("error", () => {
  const proxied = lastRawAvatar ? proxify(lastRawAvatar) : "";
  if (proxied && currentAvatar !== proxied) {
    currentAvatar = proxied;
    els.avatar.src = proxied;
    return;
  }
  if (els.avatar.getAttribute("src") !== LOCAL_AVATAR) {
    currentAvatar = LOCAL_AVATAR;
    els.avatar.src = LOCAL_AVATAR;
  }
});

/* ---------- جدولة التحديث (مش مجرد setInterval) ---------- */

let pollTimer = null;

// سلسلة طلبات: الطلب الجاي بيبدأ بعد ما يخلص اللي قبله، عشان ما يتراكموا
async function pollLoop() {
  clearTimeout(pollTimer);
  pollTimer = null;
  await syncProfile();
  if (pollingStopped) return;
  clearTimeout(pollTimer);
  pollTimer = setTimeout(pollLoop, CONFIG.refreshMs);
}

// تحديث فوري عند أي "إشارة" من المستخدم/المتصفح (بدون ما نغرق الـ API)
function requestSync(maxAgeMs = CONFIG.minRequestGapMs) {
  if (Date.now() - lastRequestAt < maxAgeMs) return;
  pollLoop();
}

showCached();
pollLoop();

// المتصفح بيوقّف المؤقتات في التبويب المخفي، فمنستغل كل فرصة نرجع فيها للواجهة
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible") requestSync(3000);
});
window.addEventListener("focus", () => requestSync(3000));
window.addEventListener("pageshow", () => requestSync(3000));
window.addEventListener("online", () => requestSync(3000));

// وأي تفاعل حقيقي مع الصفحة يعمل تحديث (حتى لو المؤقت مبطّأ)
["pointerdown", "pointermove", "keydown", "touchstart", "scroll"].forEach((evt) =>
  window.addEventListener(evt, () => requestSync(), { passive: true })
);
