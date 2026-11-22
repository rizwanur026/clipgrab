import json, os, re, shutil, tempfile, threading, time, uuid, urllib.request
from urllib.parse import urlparse
from flask import Flask, request, jsonify, Response, send_file, send_from_directory, after_this_request
from flask_cors import CORS
import yt_dlp

BASE = os.path.dirname(os.path.abspath(__file__))
app = Flask(__name__, static_folder=None)
CORS(app)

ALLOWED = ("instagram.com", "tiktok.com", "facebook.com", "fb.watch", "fb.com", "youtube.com", "youtu.be")

# ---- optional YouTube helpers (set these env vars on Render if YouTube blocks the server) ----
#   YT_COOKIES : full contents of a Netscape cookies.txt exported from a logged-in browser
#   YT_PROXY   : e.g. http://user:pass@host:port (a residential proxy avoids the datacenter-IP block)
COOKIE_FILE = None
if os.environ.get("YT_COOKIES"):
    COOKIE_FILE = os.path.join(tempfile.gettempdir(), "yt_cookies.txt")
    with open(COOKIE_FILE, "w") as _f:
        _f.write(os.environ["YT_COOKIES"])

def is_yt(url: str) -> bool:
    host = (urlparse(url).hostname or "").lower()
    return host == "youtu.be" or host == "youtube.com" or host.endswith(".youtube.com")

def base_opts(url, **extra):
    o = {"quiet": True, "noplaylist": True}
    if is_yt(url):
        if shutil.which("node"):
            o["js_runtimes"] = {"node": {}}      # YouTube needs a JS runtime for its signature challenge
        if COOKIE_FILE:
            o["cookiefile"] = COOKIE_FILE
        if os.environ.get("YT_PROXY"):
            o["proxy"] = os.environ["YT_PROXY"]
    o.update(extra)
    return o

# YouTube datacenter IPs often get the bot check on the default clients; other clients sometimes get through.
YT_CLIENT_PLANS = (None, ["web_embedded", "default"], ["mweb"], ["tv", "web_safari"])

def is_bot_block(msg: str) -> bool:
    m = msg.lower()
    return "confirm you" in m or "not a bot" in m or "sign in" in m

def extract(url, download=False, plans=None, **extra):
    """Run yt-dlp, retrying other YouTube player clients if the bot check hits. Returns (info, opts_used)."""
    plans = YT_CLIENT_PLANS if is_yt(url) else (None,)
    last = None
    for pc in plans:
        o = base_opts(url, **extra)
        if pc:
            o["extractor_args"] = {"youtube": {"player_client": pc}}
        try:
            with yt_dlp.YoutubeDL(o) as y:
                return y.extract_info(url, download=download)
        except Exception as e:
            last = e
            if not is_bot_block(str(e)):
                raise
    raise last

def yt_error(msg: str):
    m = msg.lower()
    if is_bot_block(msg):
        return "yt_blocked", "YouTube blocked our server (bot check). Use the On-phone engine."
    if "private" in m or "unavailable" in m:
        return "yt_unavailable", "This video is private or unavailable."
    return "yt_fail", "Couldn’t read this link."

def valid(url: str) -> bool:
    try:
        host = (urlparse(url).hostname or "").lower()
    except Exception:
        return False
    return urlparse(url).scheme in ("http", "https") and any(
        host == d or host.endswith("." + d) for d in ALLOWED
    )

def fmt(height, tiktok, wm=False, fid=""):
    if fid and re.fullmatch(r"[\w.\-+]{1,80}", fid):   # the exact main file picked from the list
        return f"{fid}[acodec!=none]/{fid}+ba/" + fmt(height, tiktok, wm)
    if tiktok and wm:   # the user explicitly picked the watermarked original
        return "bv*[format_id*=download]+ba/b[format_id*=download]/bv*+ba/b"
    nw = "[format_id!*=download]" if tiktok else ""  # skip watermarked TikTok stream
    h = f"[height<={height}]" if height else ""
    return f"bv*{h}{nw}+ba/b{h}{nw}/b"

def codec_name(vc):
    v = (vc or "").lower()
    if v.startswith(("avc", "h264")): return "H.264"
    if v.startswith(("hev", "hvc", "h265", "bytevc1")): return "H.265"
    if v.startswith(("vp9", "vp09")): return "VP9"
    if v.startswith(("av01", "av1")): return "AV1"
    return ""

def est(f, dur):
    n = f.get("filesize") or f.get("filesize_approx")
    if not n and f.get("tbr") and dur:
        n = f["tbr"] * 1000 / 8 * dur
    return int(n) if n else 0

def is_wm_fmt(f, tiktok):
    """TikTok's "download" stream is only a watermark file when yt-dlp says so (or says nothing).
    A clean original (like the 1080x1920 one ssstik serves) is a normal file."""
    if not tiktok or "download" not in str(f.get("format_id")):
        return False
    note = str(f.get("format_note") or "").lower()
    if note == "null":
        note = ""
    return not note or "watermark" in note

def qualities(d, tiktok, youtube=False):
    """Every real resolution of the video, biggest first (like the YouTube list).
    h     = label shown to the user (short side, so vertical reels read 1080p, not 1920p)
    sel   = real height used for the yt-dlp filter
    wm    = TikTok's watermarked original, only listed when it adds a bigger/better file
    codec = H.264 / H.265 / VP9 / AV1 when known"""
    dur, fs = d.get("duration"), d.get("formats", [])
    audio = max((f for f in fs if f.get("vcodec") in (None, "none") and f.get("acodec") not in (None, "none")),
                key=lambda f: f.get("tbr") or 0, default=None)
    vids = [f for f in fs if f.get("height") and f.get("vcodec") not in (None, "none")]
    is_wm = lambda f: is_wm_fmt(f, tiktok)
    label_of = lambda f: min(f["height"], f.get("width") or f["height"])
    # biggest file wins inside one resolution (YouTube: highest bitrate, which is what the h264-sorted download fetches)
    rank = (lambda f: (f.get("tbr") or 0, est(f, dur))) if youtube else (lambda f: (est(f, dur), f.get("tbr") or 0))
    floor = 360 if youtube else 144                               # reels can be small, don't hide them
    out = []
    for wm in ((False, True) if tiktok else (False,)):
        groups = {}
        for f in vids:
            if is_wm(f) == wm:
                groups.setdefault(label_of(f), []).append(f)
        for label, grp in groups.items():
            if label < floor:
                continue
            b = max(grp, key=rank)
            size = est(b, dur)
            if size and b.get("acodec") in (None, "none") and audio:
                size += est(audio, dur)
            out.append({"h": label, "sel": b["height"], "fid": str(b.get("format_id") or ""), "size": size, "wm": wm, "codec": codec_name(b.get("vcodec"))})
    clean = [o for o in out if not o["wm"]]
    top = max((o["h"] for o in clean), default=0)
    if tiktok:   # a watermarked file whose size yt-dlp doesn't report (no height): still offer it, label says so
        for f in fs:
            if is_wm(f) and not f.get("height") and f.get("vcodec") not in (None, "none"):
                out.append({"h": 0, "sel": "", "fid": str(f.get("format_id") or ""), "size": est(f, dur), "wm": True,
                            "codec": codec_name(f.get("vcodec"))})
                break
    wm_best = max((o for o in out if o["wm"]), key=lambda o: (o["h"], o["size"]), default=None)
    out = [o for o in out if not o["wm"] or o is wm_best]   # one watermark option: the highest one
    out.sort(key=lambda o: (o["h"], o["size"]), reverse=True)
    return out[:12]

@app.get("/")
def index():
    for d in (BASE, os.path.join(BASE, "static")):
        if os.path.exists(os.path.join(d, "index.html")):
            return send_from_directory(d, "index.html")
    return "index.html is missing from the repo.", 404

@app.post("/api/info")
def info():
    url = (request.json or {}).get("url", "").strip()
    if not valid(url):
        return jsonify(error="Paste a valid Instagram, TikTok or Facebook link."), 400
    try:
        d = extract(url)
    except Exception as e:
        code, msg = yt_error(str(e))
        return jsonify(error=msg if is_yt(url) else "Couldn’t read this link. It may be private or removed.", code=code), 422
    return jsonify(
        title=d.get("title") or "Untitled video",
        author=d.get("uploader") or d.get("channel") or "",
        thumbnail=d.get("thumbnail"),
        duration=d.get("duration"),
        qualities=qualities(d, "tiktok.com" in url, is_yt(url)),
        raw=[{k: f.get(k) for k in ("format_id", "format_note", "width", "height", "vcodec", "acodec", "tbr",
                                    "filesize", "filesize_approx", "ext")} for f in (d.get("formats") or [])][:60],
        chapters=[{"title": c.get("title"), "start": c.get("start_time"), "end": c.get("end_time")}
                  for c in (d.get("chapters") or [])][:40],
    )

@app.get("/api/image")
def image():
    url = request.args.get("url", "")
    if not valid(url):
        return jsonify(error="Invalid link."), 400
    try:
        d = extract(url)
        th = [t for t in d.get("thumbnails", []) if t.get("url")]
        th = sorted(th, key=lambda t: (t.get("width") or 0) * (t.get("height") or 0))
        src = th[-1]["url"] if th else d.get("thumbnail")
        req = urllib.request.Request(src, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=20) as r:
            data, ctype = r.read(20_000_000), r.headers.get_content_type()
    except Exception:
        return jsonify(error="Couldn’t get the image."), 500
    ext = {"image/png": "png", "image/webp": "webp"}.get(ctype, "jpg")
    return Response(data, mimetype=ctype, headers={"Content-Disposition": f"attachment; filename=image.{ext}"})

JOBS, LOCK, TTL = {}, threading.Lock(), 900

def sweep():
    now = time.time()
    for k, j in list(JOBS.items()):
        if now - j["t"] > TTL:
            shutil.rmtree(j.get("dir", ""), ignore_errors=True)
            JOBS.pop(k, None)

def run(jid, url, q, kind, start=0, end=0, precise=True, wm=False, fid=""):
    j = JOBS[jid]
    tmp = tempfile.mkdtemp()
    j["dir"] = tmp

    def hook(d):
        if d["status"] == "downloading":
            tot = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
            j["pct"] = int(d["downloaded_bytes"] * 100 / tot) if tot else 0
            j["speed"] = int(d.get("speed") or 0)

    def post(d):
        if d["status"] == "started":
            j["stage"] = "processing"

    yt = is_yt(url)
    opts = base_opts(url, outtmpl=os.path.join(tmp, "%(id)s.%(ext)s"),
                     progress_hooks=[hook], postprocessor_hooks=[post],
                     concurrent_fragment_downloads=8,
                     format_sort=["res", "vcodec:h264", "acodec:aac"] if yt else ["res", "br"])
    if end > start >= 0 and (start > 0 or end > 0):
        from yt_dlp.utils import download_range_func
        opts["download_ranges"] = download_range_func([], [(start, end)])
        opts["force_keyframes_in_cuts"] = bool(precise)       # frame-accurate cut (re-encodes, slower)
    if kind == "audio":
        opts.update(format="bestaudio/best", postprocessors=[
            {"key": "FFmpegExtractAudio", "preferredcodec": "mp3", "preferredquality": "192"}])
    else:
        opts.update(format=fmt(int(q) if q.isdigit() else None, "tiktok.com" in url, wm, fid), merge_output_format="mp4")
    try:
        extra = {k: v for k, v in opts.items() if k not in ("quiet", "noplaylist")}
        d = extract(url, download=True, **extra)
        files = sorted((os.path.join(tmp, f) for f in os.listdir(tmp)
                        if not f.endswith((".part", ".ytdl", ".temp"))), key=os.path.getsize)
        path = files[-1]
        name = re.sub(r"[^\w\- ]", "", d.get("title") or "video")[:60].strip() or "video"
        j.update(path=path, name=name + os.path.splitext(path)[1], size=os.path.getsize(path), state="ready", pct=100)
    except Exception as e:
        j["err"] = yt_error(str(e))[1] if yt else "Couldn’t fetch this one."
        j["state"] = "error"

@app.post("/api/prepare")
def prepare():
    b = request.json or {}
    url, q, kind = b.get("url", "").strip(), str(b.get("q", "best")), b.get("kind", "video")
    if not valid(url):
        return jsonify(error="Invalid link."), 400
    try:
        start, end = max(0.0, float(b.get("start") or 0)), max(0.0, float(b.get("end") or 0))
    except (TypeError, ValueError):
        return jsonify(error="Invalid time range."), 400
    if end and end <= start:
        return jsonify(error="End time must be after start time."), 400
    if end and end - start > 4 * 3600:
        return jsonify(error="Clips can be at most 4 hours."), 400
    precise = bool(b.get("precise", True))
    wm = bool(b.get("wm", False))
    fid = str(b.get("fid") or "")
    with LOCK:
        sweep()
        if sum(1 for j in JOBS.values() if j["state"] == "running") >= 3:
            return jsonify(error="Server is busy. Try again in a minute."), 429
        jid = uuid.uuid4().hex[:12]
        JOBS[jid] = {"state": "running", "stage": "downloading", "pct": 0, "speed": 0, "t": time.time()}
    threading.Thread(target=run, args=(jid, url, q, kind, start, end, precise, wm, fid), daemon=True).start()
    return jsonify(id=jid)

@app.get("/api/job/<jid>")
def job(jid):
    with LOCK:
        j = JOBS.get(jid)
    if not j:
        return jsonify(error="Expired"), 404
    return jsonify({k: j.get(k) for k in ("state", "stage", "pct", "speed", "name", "size", "err")})

@app.get("/api/file/<jid>")
def file(jid):
    j = JOBS.get(jid)
    if not j or j["state"] != "ready":
        return jsonify(error="Not ready"), 404
    return send_file(j["path"], as_attachment=True, download_name=j["name"])

# ---- app update check (the phone asks us so GitHub's per-IP rate limit on phones can't break it) ----
LATEST, LATEST_TTL = {}, 300

def _get(url, accept):
    h = {"User-Agent": "ClipGrab", "Accept": accept}
    if os.environ.get("GITHUB_TOKEN") and "api.github.com" in url:
        h["Authorization"] = "Bearer " + os.environ["GITHUB_TOKEN"]
    with urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=12) as r:
        return r.read().decode("utf-8", "replace")

def gh_latest(repo):
    try:
        j = json.loads(_get(f"https://api.github.com/repos/{repo}/releases/latest", "application/vnd.github+json"))
        apk = next((a for a in j.get("assets", []) if a.get("name", "").endswith(".apk")), None)
        if j.get("tag_name") and apk:
            return {"tag": j["tag_name"], "url": apk["browser_download_url"]}
    except Exception:
        pass
    try:   # the releases feed has no rate limit
        x = _get(f"https://github.com/{repo}/releases.atom", "application/atom+xml")
        m = re.search(r"/releases/tag/([^\"<\s]+)", x)
        if m:
            return {"tag": m.group(1), "url": f"https://github.com/{repo}/releases/download/{m.group(1)}/ClipGrab.apk"}
    except Exception:
        pass
    return None

@app.get("/api/latest")
def latest():
    repo = request.args.get("repo", "")
    if not re.fullmatch(r"[\w.-]+/[\w.-]+", repo):
        return jsonify(error="Bad repo."), 400
    c = LATEST.get(repo)
    if c and time.time() - c[0] < LATEST_TTL:
        return jsonify(c[1])
    r = gh_latest(repo)
    if not r:
        return jsonify(error="Couldn’t reach GitHub."), 502
    LATEST[repo] = (time.time(), r)
    return jsonify(r)

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", 5000)))
