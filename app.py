import os, re, shutil, tempfile
from urllib.parse import urlparse
from flask import Flask, request, jsonify, send_file, after_this_request
from flask_cors import CORS
import yt_dlp

app = Flask(__name__, static_folder="static", static_url_path="")
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

@app.get("/")
def index():
    return app.send_static_file("index.html")

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
    heights = sorted({f["height"] for f in d.get("formats", []) if f.get("height")}, reverse=True)
    return jsonify(
        title=d.get("title") or "Untitled video",
        author=d.get("uploader") or "",
        thumbnail=d.get("thumbnail"),
        duration=d.get("duration"),
        heights=heights[:5],
    )

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
