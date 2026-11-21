package com.clipgrab.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import androidx.core.content.FileProvider;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;

import kotlin.Unit;
import kotlin.jvm.functions.Function3;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** On-phone YouTube engine: runs a real yt-dlp + ffmpeg on the device (uses the phone's own IP). */
public class YtNative {
  private static class Job {
    volatile String state = "running", stage = "Starting…", error = null, result = null;
    volatile String uri = null, name = null, mime = null;
    volatile float pct = 0;
    volatile long eta = 0, size = 0;
    volatile boolean cancelled = false;
  }

  private final Context ctx;
  private final Map<String, Job> jobs = new HashMap<>();
  private final ExecutorService pool = Executors.newFixedThreadPool(3);
  private boolean inited = false;

  public YtNative(Context c) { ctx = c.getApplicationContext(); }

  private synchronized void ensureInit() throws Exception {
    if (inited) return;
    YoutubeDL.getInstance().init(ctx);
    try { FFmpeg.getInstance().init(ctx); } catch (Exception ignore) {}
    inited = true;
  }

  private String newJob() {
    String id = UUID.randomUUID().toString().substring(0, 12);
    synchronized (jobs) { jobs.put(id, new Job()); }
    return id;
  }

  private Job job(String id) { synchronized (jobs) { return jobs.get(id); } }

  private static String shorten(Throwable e) {
    String m = String.valueOf(e.getMessage());
    m = m.replace('\n', ' ');
    return m.length() > 300 ? m.substring(m.length() - 300) : m;
  }

  private static final Function3<Float, Long, String, Unit> NOOP = (p, e, l) -> Unit.INSTANCE;

  @JavascriptInterface
  public boolean available() { return true; }

  /* ---------- video info (yt-dlp -J), compacted ---------- */
  @JavascriptInterface
  public String infoStart(final String url) {
    final String id = newJob();
    final Job j = job(id);
    pool.execute(() -> {
      try {
        j.stage = "Warming up the phone engine…";
        ensureInit();
        j.stage = "Reading the video…";
        YoutubeDLRequest rq = new YoutubeDLRequest(url);
        rq.addOption("-J");
        rq.addOption("--no-playlist");
        rq.addOption("--no-warnings");
        rq.addOption("--extractor-args", "youtube:player_client=default,android_vr");
        YoutubeDLResponse r = YoutubeDL.getInstance().execute(rq, id, NOOP);
        JSONObject d = new JSONObject(r.getOut());
        JSONObject o = new JSONObject();
        o.put("title", d.optString("title", "Untitled video"));
        o.put("uploader", d.optString("uploader", d.optString("channel", "")));
        o.put("thumbnail", d.optString("thumbnail", ""));
        o.put("duration", d.optDouble("duration", 0));
        JSONArray fs = new JSONArray();
        JSONArray src = d.optJSONArray("formats");
        if (src != null) {
          for (int i = 0; i < src.length(); i++) {
            JSONObject f = src.getJSONObject(i), c = new JSONObject();
            c.put("format_id", f.optString("format_id"));
            c.put("height", f.optInt("height", 0));
            c.put("width", f.optInt("width", 0));
            c.put("vcodec", f.optString("vcodec", "none"));
            c.put("acodec", f.optString("acodec", "none"));
            c.put("tbr", f.optDouble("tbr", 0));
            c.put("filesize", f.optLong("filesize", 0));
            c.put("filesize_approx", f.optLong("filesize_approx", 0));
            fs.put(c);
          }
        }
        o.put("formats", fs);
        JSONArray chs = new JSONArray();
        JSONArray cs = d.optJSONArray("chapters");
        if (cs != null) {
          for (int i = 0; i < cs.length() && i < 40; i++) {
            JSONObject c = cs.getJSONObject(i), n = new JSONObject();
            n.put("title", c.optString("title"));
            n.put("start", c.optDouble("start_time", 0));
            n.put("end", c.optDouble("end_time", 0));
            chs.put(n);
          }
        }
        o.put("chapters", chs);
        j.result = o.toString();
        j.state = "done";
      } catch (Throwable e) {
        j.error = shorten(e);
        j.state = "error";
      }
    });
    return id;
  }

  /* ---------- download (+ optional cut) ---------- */
  @JavascriptInterface
  public String start(final String url, final String optsJson) {
    final String id = newJob();
    final Job j = job(id);
    pool.execute(() -> {
      File dir = new File(ctx.getCacheDir(), "yt/" + id);
      try {
        JSONObject o = new JSONObject(optsJson);
        String q = o.optString("q", "best");
        boolean audio = "audio".equals(o.optString("kind"));
        double s = o.optDouble("start", 0), e = o.optDouble("end", 0);
        boolean precise = o.optBoolean("precise", true);
        String base = o.optString("name", "video").replaceAll("[^\\p{L}\\p{N} _-]", "").trim();
        if (base.isEmpty()) base = "video";
        if (base.length() > 60) base = base.substring(0, 60).trim();

        j.stage = "Warming up the phone engine…";
        ensureInit();
        if (j.cancelled) return;
        dir.mkdirs();

        YoutubeDLRequest rq = new YoutubeDLRequest(url);
        rq.addOption("--no-playlist");
        rq.addOption("--no-mtime");
        rq.addOption("--no-warnings");
        rq.addOption("-N", "4");
        rq.addOption("--extractor-args", "youtube:player_client=default,android_vr");
        rq.addOption("-o", dir.getAbsolutePath() + "/%(id)s.%(ext)s");
        if (audio) {
          rq.addOption("-f", "bestaudio/best");
          rq.addOption("-x");
          rq.addOption("--audio-format", "mp3");
          rq.addOption("--audio-quality", "192K");
        } else {
          boolean best = q.equals("best") || !q.matches("\\d+");
          String f = best ? "bv*+ba/b" : "bv*[height<=" + q + "]+ba/b[height<=" + q + "]/b";
          rq.addOption("-f", f);
          rq.addOption("-S", "res,vcodec:h264,acodec:aac");
          rq.addOption("--merge-output-format", "mp4");
        }
        if (e > s && (s > 0 || e > 0)) {
          rq.addOption("--download-sections", "*" + (long) s + "-" + (long) e);
          if (precise) rq.addOption("--force-keyframes-in-cuts");
        }

        j.stage = "Downloading…";
        Function3<Float, Long, String, Unit> cb = (p, eta, line) -> {
          if (p != null && p >= 0) j.pct = p;
          if (eta != null && eta >= 0) j.eta = eta;
          if (line != null) {
            if (line.contains("[Merger]") || line.contains("[VideoRemuxer]") || line.contains("[ExtractAudio]")
                || line.contains("[ffmpeg]") || line.contains("[VideoConvertor]")) j.stage = "Merging & cutting…";
            else if (line.contains("[download]")) j.stage = "Downloading…";
          }
          return Unit.INSTANCE;
        };
        YoutubeDL.getInstance().execute(rq, id, cb);
        if (j.cancelled) return;

        File best = null;
        File[] fs = dir.listFiles();
        if (fs != null) {
          for (File f : fs) {
            String n = f.getName();
            if (n.endsWith(".part") || n.endsWith(".ytdl") || n.endsWith(".temp") || n.endsWith(".json")) continue;
            if (best == null || f.length() > best.length()) best = f;
          }
        }
        if (best == null || best.length() == 0) throw new Exception("No file was produced");

        j.stage = "Saving to your phone…";
        j.pct = 100;
        String ext = best.getName().substring(best.getName().lastIndexOf('.') + 1).toLowerCase();
        String mime = ext.equals("mp3") ? "audio/mpeg" : ext.equals("mp4") ? "video/mp4"
            : ext.equals("webm") ? "video/webm" : ext.equals("mkv") ? "video/x-matroska"
            : ext.equals("m4a") ? "audio/mp4" : "application/octet-stream";
        String outName = base + "." + ext;
        Uri u = saveToDownloads(best, outName, mime);
        j.uri = u.toString();
        j.name = outName;
        j.mime = mime;
        j.size = best.length();
        j.state = "done";
      } catch (Throwable ex) {
        if (!j.cancelled) { j.error = shorten(ex); j.state = "error"; }
      } finally {
        deleteDir(dir);
      }
    });
    return id;
  }

  private Uri saveToDownloads(File f, String name, String mime) throws Exception {
    if (Build.VERSION.SDK_INT >= 29) {
      ContentResolver cr = ctx.getContentResolver();
      ContentValues v = new ContentValues();
      v.put(MediaStore.Downloads.DISPLAY_NAME, name);
      v.put(MediaStore.Downloads.MIME_TYPE, mime);
      v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
      v.put(MediaStore.Downloads.IS_PENDING, 1);
      Uri u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
      if (u == null) throw new Exception("Couldn’t create the file in Downloads");
      try (InputStream in = new FileInputStream(f); OutputStream out = cr.openOutputStream(u)) {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
      }
      ContentValues done = new ContentValues();
      done.put(MediaStore.Downloads.IS_PENDING, 0);
      cr.update(u, done, null, null);
      return u;
    }
    File dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
    if (dir == null) dir = ctx.getFilesDir();
    dir.mkdirs();
    File dst = new File(dir, name);
    try (InputStream in = new FileInputStream(f); OutputStream out = new java.io.FileOutputStream(dst)) {
      byte[] buf = new byte[1 << 16];
      int n;
      while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
    return FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", dst);
  }

  private static void deleteDir(File d) {
    File[] fs = d.listFiles();
    if (fs != null) for (File f : fs) { if (f.isDirectory()) deleteDir(f); else f.delete(); }
    d.delete();
  }

  /* ---------- yt-dlp self-update ---------- */
  @JavascriptInterface
  public String updateStart() {
    final String id = newJob();
    final Job j = job(id);
    pool.execute(() -> {
      try {
        j.stage = "Updating the engine…";
        ensureInit();
        Object channel = findStableChannel();
        Object status = null;
        for (Method m : YoutubeDL.getInstance().getClass().getMethods()) {
          if (!m.getName().equals("updateYoutubeDL")) continue;
          Class<?>[] pt = m.getParameterTypes();
          if (pt.length == 2 && channel != null) { status = m.invoke(YoutubeDL.getInstance(), ctx, channel); break; }
          if (pt.length == 1) { status = m.invoke(YoutubeDL.getInstance(), ctx); break; }
        }
        j.result = String.valueOf(status);
        j.state = "done";
      } catch (Throwable e) {
        j.error = shorten(e);
        j.state = "error";
      }
    });
    return id;
  }

  private static Object findStableChannel() {
    try {
      Class<?> c = Class.forName("com.yausername.youtubedl_android.YoutubeDL$UpdateChannel");
      for (String n : new String[]{"STABLE", "_STABLE", "Stable"}) {
        try { Object o = c.getField(n).get(null); if (o != null) return o; } catch (Exception ignore) {}
      }
      for (Class<?> k : c.getDeclaredClasses()) {
        if (k.getSimpleName().equalsIgnoreCase("STABLE")) {
          try { return k.getField("INSTANCE").get(null); } catch (Exception ignore) {}
        }
      }
      Object[] en = c.getEnumConstants();
      if (en != null) for (Object o : en) if (o.toString().toUpperCase().contains("STABLE")) return o;
    } catch (Throwable ignore) {}
    return null;
  }

  /* ---------- polling + cancel ---------- */
  @JavascriptInterface
  public String poll(String id) {
    Job j = job(id);
    JSONObject o = new JSONObject();
    try {
      if (j == null) { o.put("state", "error"); o.put("error", "Unknown job"); return o.toString(); }
      o.put("state", j.state);
      o.put("stage", j.stage);
      o.put("pct", j.pct);
      o.put("eta", j.eta);
      o.put("error", j.error == null ? JSONObject.NULL : j.error);
      o.put("result", j.result == null ? JSONObject.NULL : j.result);
      o.put("uri", j.uri == null ? JSONObject.NULL : j.uri);
      o.put("name", j.name == null ? JSONObject.NULL : j.name);
      o.put("mime", j.mime == null ? JSONObject.NULL : j.mime);
      o.put("size", j.size);
    } catch (Exception ignore) {}
    return o.toString();
  }

  @JavascriptInterface
  public void cancel(String id) {
    Job j = job(id);
    if (j != null) { j.cancelled = true; j.state = "cancelled"; }
    try { YoutubeDL.getInstance().destroyProcessById(id); } catch (Throwable ignore) {}
  }

  /* ---------- file access for files saved through MediaStore ---------- */
  @JavascriptInterface
  public boolean existsUri(String uri) {
    try { ctx.getContentResolver().openFileDescriptor(Uri.parse(uri), "r").close(); return true; }
    catch (Exception e) { return false; }
  }

  @JavascriptInterface
  public boolean openUri(String uri, String mime) {
    try {
      Intent i = new Intent(Intent.ACTION_VIEW);
      i.setDataAndType(Uri.parse(uri), mime);
      i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
      ctx.startActivity(i);
      return true;
    } catch (Exception e) { return false; }
  }

  @JavascriptInterface
  public boolean shareUri(String uri, String mime) {
    try {
      Intent i = new Intent(Intent.ACTION_SEND);
      i.setType(mime);
      i.putExtra(Intent.EXTRA_STREAM, Uri.parse(uri));
      i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      Intent c = Intent.createChooser(i, "Share");
      c.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      ctx.startActivity(c);
      return true;
    } catch (Exception e) { return false; }
  }

  @JavascriptInterface
  public boolean deleteUri(String uri) {
    try { return ctx.getContentResolver().delete(Uri.parse(uri), null, null) > 0; }
    catch (Exception e) { return false; }
  }
}
