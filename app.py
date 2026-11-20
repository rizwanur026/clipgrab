import os, re, shutil, tempfile, threading, time, uuid, urllib.request
from urllib.parse import urlparse
from flask import Flask, request, jsonify, Response, send_file, send_from_directory, after_this_request
from flask_cors import CORS
import yt_dlp

BASE = os.path.dirname(os.path.abspath(__file__))
app = Flask(__name__, static_folder=None)
CORS(app)

ALLOWED = ("instagram.com", "tiktok.com", "facebook.com", "fb.watch", "fb.com")

def valid(url: str) -> bool:
    try:
        host = (urlparse(url).hostname or "").lower()
    except Exception:
        return False
    return urlparse(url).scheme in ("http", "https") and any(
        host == d or host.endswith("." + d) for d in ALLOWED
    )

def fmt(height, tiktok):
    nw = "[format_id!*=download]" if tiktok else ""  # skip watermarked TikTok stream
    h = f"[height<={height}]" if height else ""
    return f"bv*{h}{nw}+ba/b{h}{nw}/b"

def est(f, dur):
    n = f.get("filesize") or f.get("filesize_approx")
    if not n and f.get("tbr") and dur:
        n = f["tbr"] * 1000 / 8 * dur
    return int(n) if n else 0

def qualities(d, tiktok):
    dur, fs = d.get("duration"), d.get("formats", [])
    audio = max((f for f in fs if f.get("vcodec") in (None, "none") and f.get("acodec") not in (None, "none")),
                key=lambda f: f.get("tbr") or 0, default=None)
    out = []
    for h in sorted({f["height"] for f in fs if f.get("height")}, reverse=True)[:5]:
        c = [f for f in fs if f.get("height") == h and f.get("vcodec") not in (None, "none")
             and not (tiktok and "download" in str(f.get("format_id")))]
        if not c:
            continue
        b = max(c, key=lambda f: f.get("tbr") or 0)
        size = est(b, dur)
        if size and b.get("acodec") in (None, "none") and audio:
            size += est(audio, dur)
        out.append({"h": h, "size": size})
    return out

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
        with yt_dlp.YoutubeDL({"quiet": True, "noplaylist": True}) as y:
            d = y.extract_info(url, download=False)
    except Exception:
        return jsonify(error="Couldn’t read this link. It may be private or removed."), 422
    return jsonify(
        title=d.get("title") or "Untitled video",
        author=d.get("uploader") or "",
        thumbnail=d.get("thumbnail"),
        duration=d.get("duration"),
        qualities=qualities(d, "tiktok.com" in url),
    )

@app.get("/api/image")
def image():
    url = request.args.get("url", "")
    if not valid(url):
        return jsonify(error="Invalid link."), 400
    try:
        with yt_dlp.YoutubeDL({"quiet": True, "noplaylist": True}) as y:
            d = y.extract_info(url, download=False)
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

def run(jid, url, q, kind):
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

    opts = {"quiet": True, "noplaylist": True, "outtmpl": os.path.join(tmp, "%(id)s.%(ext)s"),
            "progress_hooks": [hook], "postprocessor_hooks": [post],
            "concurrent_fragment_downloads": 8, "format_sort": ["res", "br"]}
    if kind == "audio":
        opts.update(format="bestaudio/best", postprocessors=[
            {"key": "FFmpegExtractAudio", "preferredcodec": "mp3", "preferredquality": "192"}])
    else:
        opts.update(format=fmt(int(q) if q.isdigit() else None, "tiktok.com" in url), merge_output_format="mp4")
    try:
        with yt_dlp.YoutubeDL(opts) as y:
            d = y.extract_info(url, download=True)
        files = sorted((os.path.join(tmp, f) for f in os.listdir(tmp)
                        if not f.endswith((".part", ".ytdl"))), key=os.path.getsize)
        path = files[-1]
        name = re.sub(r"[^\w\- ]", "", d.get("title") or "video")[:60].strip() or "video"
        j.update(path=path, name=name + os.path.splitext(path)[1], size=os.path.getsize(path), state="ready", pct=100)
    except Exception:
        j["state"] = "error"

@app.post("/api/prepare")
def prepare():
    b = request.json or {}
    url, q, kind = b.get("url", "").strip(), str(b.get("q", "best")), b.get("kind", "video")
    if not valid(url):
        return jsonify(error="Invalid link."), 400
    with LOCK:
        sweep()
        if sum(1 for j in JOBS.values() if j["state"] == "running") >= 3:
            return jsonify(error="Server is busy. Try again in a minute."), 429
        jid = uuid.uuid4().hex[:12]
        JOBS[jid] = {"state": "running", "stage": "downloading", "pct": 0, "speed": 0, "t": time.time()}
    threading.Thread(target=run, args=(jid, url, q, kind), daemon=True).start()
    return jsonify(id=jid)

@app.get("/api/job/<jid>")
def job(jid):
    with LOCK:
        j = JOBS.get(jid)
    if not j:
        return jsonify(error="Expired"), 404
    return jsonify({k: j.get(k) for k in ("state", "stage", "pct", "speed", "name", "size")})

@app.get("/api/file/<jid>")
def file(jid):
    j = JOBS.get(jid)
    if not j or j["state"] != "ready":
        return jsonify(error="Not ready"), 404
    return send_file(j["path"], as_attachment=True, download_name=j["name"])

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", 5000)))
