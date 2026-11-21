package com.clipgrab.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.graphics.Color;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
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

  private Activity act;
  public YtNative(Context c) { ctx = c.getApplicationContext(); act = c instanceof Activity ? (Activity) c : null; }

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


  /* ---------- robust execution: client fallbacks + one-time auto-update to nightly yt-dlp ---------- */
  // null = let yt-dlp pick its own default clients (recommended; hard-coding clients is what broke the engine).
  private static final String[] CLIENT_PLANS = {
    null,
    "youtube:player_client=default,web_embedded",
    "youtube:player_client=mweb,web_safari",
    "youtube:player_client=default,android_vr"
  };
  private static final java.util.regex.Pattern FATAL = java.util.regex.Pattern.compile(
      "private video|video unavailable|this video is unavailable|has been removed|members-only|join this channel"
      + "|is not available in your country|live event will begin|premieres in|copyright", java.util.regex.Pattern.CASE_INSENSITIVE);
  private volatile boolean autoUpdated = false;

  private interface ReqFactory { YoutubeDLRequest make(String extractorArgs); }

  private synchronized void autoUpdateOnce() {
    if (autoUpdated) return;
    autoUpdated = true;
    try { doUpdate(); } catch (Throwable ignore) {}
  }

  private static void clearDir(File d) {
    File[] fs = d == null ? null : d.listFiles();
    if (fs != null) for (File f : fs) { if (f.isDirectory()) deleteDir(f); else f.delete(); }
  }

  private YoutubeDLResponse runPlans(Job j, String id, String url, File dir, ReqFactory fac, Function3<Float, Long, String, Unit> cb) throws Throwable {
    Throwable last = null;
    final String[] plans = isYouTube(url) ? CLIENT_PLANS : new String[]{null};
    int i = 0;
    while (i < plans.length) {
      if (j.cancelled) throw new Exception("cancelled");
      try {
        return YoutubeDL.getInstance().execute(fac.make(plans[i]), id, cb);
      } catch (Throwable e) {
        last = e;
        if (j.cancelled) throw e;
        if (FATAL.matcher(String.valueOf(e.getMessage())).find()) throw e;
        if (dir != null) clearDir(dir);
        // first failure: the bundled yt-dlp is probably stale -> pull the nightly build once, then start over
        if (!autoUpdated) {
          j.stage = "Updating the YouTube engine…";
          autoUpdateOnce();
          i = 0;
          continue;
        }
        j.stage = "Trying another way…";
        i++;
      }
    }
    throw last != null ? last : new Exception("The phone engine failed");
  }

  @JavascriptInterface
  public boolean available() { return true; }

  /* ---------- video info (yt-dlp -J), compacted ---------- */
  @JavascriptInterface
  public String infoStart(final String url) {
    final String id = newJob();
    final Job j = job(id);
    pool.execute(() -> {
      File ck = null;
      try {
        j.stage = "Warming up the phone engine…";
        ensureInit();
        j.stage = "Reading the video…";
        ck = jobCookies(id, url);
        final File fck = ck;
        YoutubeDLResponse r = runPlans(j, id, url, null, ea -> {
          YoutubeDLRequest rq = new YoutubeDLRequest(url);
          if (fck != null) rq.addOption("--cookies", fck.getAbsolutePath());
          rq.addOption("-J");
          rq.addOption("--no-playlist");
          rq.addOption("--no-warnings");
          if (ea != null) rq.addOption("--extractor-args", ea);
          return rq;
        }, NOOP);
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
      } finally {
        if (ck != null) ck.delete();
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
      File ck = null;
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

        ck = jobCookies(id, url);
        final File fck = ck;
        final boolean tt = isTikTok(url);
        final boolean fAudio = audio, fPrecise = precise;
        final double fs0 = s, fe0 = e;
        final String fq = q;
        ReqFactory fac = ea -> {
          YoutubeDLRequest rq = new YoutubeDLRequest(url);
          if (fck != null) rq.addOption("--cookies", fck.getAbsolutePath());
          rq.addOption("--no-playlist");
          rq.addOption("--no-mtime");
          rq.addOption("--no-warnings");
          rq.addOption("-N", "4");
          rq.addOption("--retries", "5");
          rq.addOption("--fragment-retries", "5");
          if (ea != null) rq.addOption("--extractor-args", ea);
          rq.addOption("-o", dir.getAbsolutePath() + "/%(id)s.%(ext)s");
          if (fAudio) {
            rq.addOption("-f", "bestaudio/best");
            rq.addOption("-x");
            rq.addOption("--audio-format", "mp3");
            rq.addOption("--audio-quality", "192K");
          } else {
            boolean best = fq.equals("best") || !fq.matches("\\d+");
            String h = best ? "" : "[height<=" + fq + "]";
            String nw = tt ? "[format_id!*=download]" : "";   // skip the watermarked TikTok stream
            rq.addOption("-f", "bv*" + h + nw + "+ba/b" + h + nw + "/b");
            rq.addOption("-S", tt ? "res,br" : "res,vcodec:h264,acodec:aac");
            rq.addOption("--merge-output-format", "mp4");
          }
          if (fe0 > fs0 && (fs0 > 0 || fe0 > 0)) {
            rq.addOption("--download-sections", "*" + (long) fs0 + "-" + (long) fe0);
            if (fPrecise) rq.addOption("--force-keyframes-in-cuts");
          }
          return rq;
        };

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
        runPlans(j, id, url, dir, fac, cb);
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
        if (ck != null) ck.delete();
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


  /* ---------- TikTok login (cookies stay on this phone, used only by the on-phone yt-dlp) ---------- */
  private volatile boolean ttOpen = false;
  private static final java.util.regex.Pattern SESSION = java.util.regex.Pattern.compile("(^|;\\s*)sessionid=");

  private static String hostOf(String url) {
    try { String h = Uri.parse(url).getHost(); return h == null ? "" : h.toLowerCase(); } catch (Exception e) { return ""; }
  }
  private static boolean isTikTok(String url) { String h = hostOf(url); return h.equals("tiktok.com") || h.endsWith(".tiktok.com"); }
  private static boolean isYouTube(String url) { String h = hostOf(url); return h.equals("youtu.be") || h.equals("youtube.com") || h.endsWith(".youtube.com"); }

  private File cookieFile() { return new File(ctx.getFilesDir(), "tt_cookies.txt"); }

  private boolean loggedIn() {
    File f = cookieFile();
    if (!f.exists()) return false;
    try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) {
      String line;
      while ((line = r.readLine()) != null) if (line.contains("\tsessionid\t")) return true;
    } catch (Exception ignore) {}
    return false;
  }

  /** Private per-job copy (yt-dlp rewrites the cookie file on exit, so jobs must not share one). */
  private File jobCookies(String id, String url) {
    if (!isTikTok(url) || !loggedIn()) return null;
    try {
      File dst = new File(ctx.getCacheDir(), "yt/" + id + ".cookies");
      dst.getParentFile().mkdirs();
      try (InputStream in = new FileInputStream(cookieFile()); OutputStream out = new java.io.FileOutputStream(dst)) {
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
      }
      return dst;
    } catch (Exception e) { return null; }
  }

  private boolean saveCookies() {
    String raw = CookieManager.getInstance().getCookie("https://www.tiktok.com");
    if (raw == null || !SESSION.matcher(raw).find()) return false;
    long exp = System.currentTimeMillis() / 1000 + 180L * 86400;
    StringBuilder sb = new StringBuilder("# Netscape HTTP Cookie File\n");
    for (String part : raw.split(";")) {
      int i = part.indexOf('=');
      if (i <= 0) continue;
      sb.append(".tiktok.com\tTRUE\t/\tTRUE\t").append(exp).append('\t')
        .append(part.substring(0, i).trim()).append('\t').append(part.substring(i + 1).trim()).append('\n');
    }
    try (java.io.FileOutputStream out = new java.io.FileOutputStream(cookieFile())) {
      out.write(sb.toString().getBytes("UTF-8"));
      return true;
    } catch (Exception e) { return false; }
  }

  @JavascriptInterface
  public String ttStatus() {
    JSONObject o = new JSONObject();
    try { o.put("in", loggedIn()); o.put("open", ttOpen); } catch (Exception ignore) {}
    return o.toString();
  }

  @JavascriptInterface
  public boolean ttLogin() {
    if (act == null || ttOpen) return false;
    ttOpen = true;
    act.runOnUiThread(() -> {
      try { showTtLogin(); } catch (Throwable t) { ttOpen = false; }
    });
    return true;
  }

  @JavascriptInterface
  public boolean ttLogout() {
    try { cookieFile().delete(); } catch (Throwable ignore) {}
    Runnable clear = () -> {
      try {
        CookieManager cm = CookieManager.getInstance();
        String raw = cm.getCookie("https://www.tiktok.com");
        if (raw != null) for (String part : raw.split(";")) {
          int i = part.indexOf('=');
          if (i <= 0) continue;
          String n = part.substring(0, i).trim();
          cm.setCookie("https://www.tiktok.com", n + "=; Max-Age=0; Path=/; Domain=.tiktok.com");
          cm.setCookie("https://www.tiktok.com", n + "=; Max-Age=0; Path=/");
        }
        cm.flush();
      } catch (Throwable ignore) {}
    };
    if (act != null) act.runOnUiThread(clear); else clear.run();
    return !loggedIn();
  }

  private void showTtLogin() {
    final Dialog dlg = new Dialog(act, android.R.style.Theme_Black_NoTitleBar);
    LinearLayout root = new LinearLayout(act);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(Color.parseColor("#0b0b14"));

    LinearLayout bar = new LinearLayout(act);
    bar.setOrientation(LinearLayout.HORIZONTAL);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    bar.setPadding(32, 16, 32, 16);
    TextView title = new TextView(act);
    title.setText("Log in to TikTok");
    title.setTextColor(Color.WHITE);
    title.setTextSize(17);
    title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    Button done = new Button(act); done.setText("Done");
    Button close = new Button(act); close.setText("Close");
    bar.addView(title); bar.addView(close); bar.addView(done);

    final WebView wv = new WebView(act);
    WebSettings ws = wv.getSettings();
    ws.setJavaScriptEnabled(true);
    ws.setDomStorageEnabled(true);
    ws.setUserAgentString(ws.getUserAgentString().replace("; wv", "").replace("Version/4.0 ", ""));
    CookieManager cm = CookieManager.getInstance();
    cm.setAcceptCookie(true);
    cm.setAcceptThirdPartyCookies(wv, true);
    wv.setWebViewClient(new WebViewClient() {
      @Override public void onPageFinished(WebView v, String u) {
        if (saveCookies()) {
          Toast.makeText(act, "TikTok login saved", Toast.LENGTH_SHORT).show();
          dlg.dismiss();
        }
      }
    });
    root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    root.addView(wv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    dlg.setContentView(root);
    dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

    done.setOnClickListener(v -> {
      if (saveCookies()) { Toast.makeText(act, "TikTok login saved", Toast.LENGTH_SHORT).show(); dlg.dismiss(); }
      else Toast.makeText(act, "Not logged in yet. Finish logging in first.", Toast.LENGTH_SHORT).show();
    });
    close.setOnClickListener(v -> dlg.dismiss());
    dlg.setOnKeyListener((d, k, e) -> {
      if (k == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) {
        if (wv.canGoBack()) wv.goBack(); else dlg.dismiss();
        return true;
      }
      return false;
    });
    dlg.setOnDismissListener(d -> {
      ttOpen = false;
      root.removeAllViews();
      wv.stopLoading();
      wv.destroy();
    });
    dlg.show();
    wv.loadUrl("https://www.tiktok.com/login");
  }

  /* ---------- yt-dlp self-update (nightly first: YouTube fixes land there before stable) ---------- */
  private String doUpdate() throws Throwable {
    ensureInit();
    Object status = null;
    for (String name : new String[]{"NIGHTLY", "STABLE"}) {
      Object channel = findChannel(name);
      if (channel == null) continue;
      try {
        for (Method m : YoutubeDL.getInstance().getClass().getMethods()) {
          if (!m.getName().equals("updateYoutubeDL")) continue;
          Class<?>[] pt = m.getParameterTypes();
          if (pt.length == 2) { status = m.invoke(YoutubeDL.getInstance(), ctx, channel); break; }
        }
        if (status != null) break;
      } catch (Throwable ignore) { /* try the next channel */ }
    }
    if (status == null) {
      for (Method m : YoutubeDL.getInstance().getClass().getMethods()) {
        if (m.getName().equals("updateYoutubeDL") && m.getParameterTypes().length == 1) { status = m.invoke(YoutubeDL.getInstance(), ctx); break; }
      }
    }
    return String.valueOf(status);
  }

  @JavascriptInterface
  public String updateStart() {
    final String id = newJob();
    final Job j = job(id);
    pool.execute(() -> {
      try {
        j.stage = "Updating the engine…";
        j.result = doUpdate();
        autoUpdated = true;
        j.state = "done";
      } catch (Throwable e) {
        j.error = shorten(e);
        j.state = "error";
      }
    });
    return id;
  }

  private static Object findChannel(String name) {
    String[] classes = {"com.yausername.youtubedl_android.YoutubeDL$UpdateChannel", "com.yausername.youtubedl_android.UpdateChannel"};
    for (String cn : classes) {
      try {
        Class<?> c = Class.forName(cn);
        for (String n : new String[]{name, "_" + name, name.charAt(0) + name.substring(1).toLowerCase()}) {
          try { Object o = c.getField(n).get(null); if (o != null) return o; } catch (Exception ignore) {}
        }
        for (Class<?> k : c.getDeclaredClasses()) {
          if (k.getSimpleName().equalsIgnoreCase(name) || k.getSimpleName().equalsIgnoreCase("_" + name)) {
            try { return k.getField("INSTANCE").get(null); } catch (Exception ignore) {}
          }
        }
        Object[] en = c.getEnumConstants();
        if (en != null) for (Object o : en) if (o.toString().toUpperCase().contains(name)) return o;
      } catch (Throwable ignore) {}
    }
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
