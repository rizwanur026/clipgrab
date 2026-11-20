import os, re, shutil, tempfile, urllib.request
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

@app.get("/api/formats")
def formats():
    url = request.args.get("url", "")
    if not valid(url):
        return jsonify(error="Invalid link."), 400
    try:
        with yt_dlp.YoutubeDL({"quiet": True, "noplaylist": True}) as y:
            d = y.extract_info(url, download=False)
    except Exception as e:
        return jsonify(error=str(e)[:300]), 422
    return jsonify([
        {"id": f.get("format_id"), "height": f.get("height"), "kbps": round(f.get("tbr") or 0),
         "codec": f.get("vcodec"), "note": f.get("format_note")}
        for f in d.get("formats", [])
    ])

@app.get("/api/download")
def download():
    url = request.args.get("url", "")
    q = request.args.get("q", "best")
    if not valid(url):
        return jsonify(error="Invalid link."), 400
    height = int(q) if q.isdigit() else None
    tmp = tempfile.mkdtemp()
    opts = {
        "quiet": True, "noplaylist": True,
        "outtmpl": os.path.join(tmp, "%(id)s.%(ext)s"),
        "format": fmt(height, "tiktok.com" in url),
        "merge_output_format": "mp4",
        "format_sort": ["res", "br"],  # biggest resolution first, then highest bitrate
    }
    try:
        with yt_dlp.YoutubeDL(opts) as y:
            d = y.extract_info(url, download=True)
            path = y.prepare_filename(d)
            if not os.path.exists(path):
                path = os.path.splitext(path)[0] + ".mp4"
    except Exception:
        shutil.rmtree(tmp, ignore_errors=True)
        return jsonify(error="Download failed. Try again or pick another quality."), 500

    @after_this_request
    def cleanup(resp):
        shutil.rmtree(tmp, ignore_errors=True)
        return resp

    name = re.sub(r"[^\w\- ]", "", d.get("title") or "video")[:60].strip() or "video"
    return send_file(path, as_attachment=True, download_name=f"{name}.mp4")

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", 5000)))
