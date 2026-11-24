package com.clipgrab.app;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI Enhance: upscales a video on the phone with Real-ESRGAN (ncnn + Vulkan), frame by frame.
 * Pipeline: MediaCodec decode -> native AI upscale -> resize -> MediaCodec H.264 encode -> MediaMuxer (+ the original sound).
 * The JS side talks to it as window.ClipAI.
 */
public final class Enhancer {
  private static final int PICK = 9421;
  private static final String MODEL = "realesr-animevideov3-x2";   // 2x model, small and fast
  private static final int MODEL_SCALE = 2;
  private static final int COLOR_FLEX = 0x7F420888;               // COLOR_FormatYUV420Flexible

  private static boolean libOk;
  private static String libErr = "";

  static {
    try {
      System.loadLibrary("clipenhance");
      libOk = true;
    } catch (Throwable t) {
      libErr = String.valueOf(t.getMessage());
    }
  }

  private static native int nInit(String param, String bin, int scale, int tile);
  private static native int nUpscale(int[] in, int w, int h, int[] out);
  private static native void nSetTile(int tile);
  private static native void nRelease();
  private static native String nGpu();

  private static final class Job {
    volatile String state = "run", stage = "Starting…", error, uri, name, mime;
    volatile int pct;
    volatile long eta, size;
    volatile boolean cancelled;
  }

  private static final class Cancelled extends Exception {}

  private final Context ctx;
  private final Activity act;
  private final ExecutorService pool = Executors.newSingleThreadExecutor();
  private final Map<String, Job> jobs = new ConcurrentHashMap<>();
  private volatile String gpuName = "";
  private volatile String initErr = "";

  public Enhancer(Context c) {
    ctx = c.getApplicationContext();
    act = c instanceof Activity ? (Activity) c : null;
  }

  /* ---------- keep running in the background ---------- */
  private void svc(boolean on) {
    try {
      Class<?> c = Class.forName("com.clipgrab.app.DlService");
      c.getMethod(on ? "hold" : "release", Context.class).invoke(null, ctx);
    } catch (Throwable ignore) {}
  }

  /* ---------- JS API ---------- */
  @JavascriptInterface
  public boolean available() { return libOk && hasModel(); }

  private boolean hasModel() {
    try {
      for (String n : ctx.getAssets().list("ai")) if (n.equals(MODEL + ".param")) return true;
    } catch (Exception ignore) {}
    return false;
  }

  @JavascriptInterface
  public String diag() {
    JSONObject o = new JSONObject();
    try {
      o.put("lib", libOk);
      o.put("libErr", libErr);
      o.put("model", hasModel());
      o.put("gpu", gpuName);
      o.put("initErr", initErr);
    } catch (Exception ignore) {}
    return o.toString();
  }

  /** Opens the system file picker for a video. The answer comes back through window.onAIPick(...). */
  @JavascriptInterface
  public void pick() {
    if (act == null) return;
    act.runOnUiThread(() -> {
      try {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("video/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        act.startActivityForResult(Intent.createChooser(i, "Choose a video"), PICK);
      } catch (Exception e) {
        sendPick("{\"error\":\"Couldn’t open the picker\"}");
      }
    });
  }

  /** Called by MainActivity.onActivityResult. */
  public void onPicked(int requestCode, int resultCode, Intent data) {
    if (requestCode != PICK) return;
    if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
      sendPick("{\"cancelled\":true}");
      return;
    }
    final Uri u = data.getData();
    pool.execute(() -> {
      try {
        // copy into the app's own cache: the file stays readable for as long as we need it
        File dir = ctx.getCacheDir();
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) if (f.getName().startsWith("ai_src_")) f.delete();
        File dst = new File(dir, "ai_src_" + System.currentTimeMillis() + ".mp4");
        try (InputStream in = ctx.getContentResolver().openInputStream(u); OutputStream out = new FileOutputStream(dst)) {
          if (in == null) throw new Exception("Couldn’t read that file");
          byte[] b = new byte[1 << 16];
          int n;
          while ((n = in.read(b)) > 0) out.write(b, 0, n);
        }
        JSONObject o = new JSONObject();
        o.put("src", Uri.fromFile(dst).toString());
        o.put("name", displayName(u));
        o.put("size", dst.length());
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
          r.setDataSource(dst.getAbsolutePath());
          o.put("w", toInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)));
          o.put("h", toInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)));
          o.put("rot", toInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)));
          o.put("dur", toInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)));
        } finally {
          try { r.release(); } catch (Exception ignore) {}
        }
        if (o.getInt("w") <= 0 || o.getInt("h") <= 0) throw new Exception("That doesn’t look like a video");
        sendPick(o.toString());
      } catch (Exception e) {
        JSONObject o = new JSONObject();
        try { o.put("error", String.valueOf(e.getMessage())); } catch (Exception ignore) {}
        sendPick(o.toString());
      }
    });
  }

  private static int toInt(String s) {
    try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
  }

  private String displayName(Uri u) {
    try (Cursor c = ctx.getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (c != null && c.moveToFirst()) {
        String n = c.getString(0);
        if (n != null && !n.isEmpty()) return n;
      }
    } catch (Exception ignore) {}
    return "video.mp4";
  }

  private void sendPick(final String json) {
    if (act == null) return;
    act.runOnUiThread(() -> {
      try {
        WebView wv = ((com.getcapacitor.BridgeActivity) act).getBridge().getWebView();
        wv.evaluateJavascript("window.onAIPick && window.onAIPick(" + json + ")", null);
      } catch (Throwable ignore) {}
    });
  }

  @JavascriptInterface
  public String start(final String src, final String optsJson) {
    final String id = Long.toString(System.nanoTime(), 36);
    final Job j = new Job();
    jobs.put(id, j);
    svc(true);
    pool.execute(() -> {
      File out = null;
      try {
        out = new File(ctx.getCacheDir(), "ai_out_" + id + ".mp4");
        run(j, Uri.parse(src), new JSONObject(optsJson), out);
        if (j.cancelled) throw new Cancelled();
        String name = new JSONObject(optsJson).optString("outName", "enhanced.mp4");
        j.stage = "Saving to Downloads…";
        Uri u = saveToDownloads(out, name, "video/mp4");
        j.uri = u.toString();
        j.name = name;
        j.mime = "video/mp4";
        j.size = out.length();
        j.pct = 100;
        j.state = "done";
      } catch (Cancelled c) {
        j.state = "cancelled";
      } catch (Throwable t) {
        j.error = friendly(t);
        j.state = "error";
      } finally {
        if (out != null) out.delete();
        try { nRelease(); } catch (Throwable ignore) {}
        svc(false);
      }
    });
    return id;
  }

  private static String friendly(Throwable t) {
    if (t instanceof OutOfMemoryError) return "The phone ran out of memory. Try a shorter clip or a smaller size.";
    String m = String.valueOf(t.getMessage());
    return m.equals("null") ? t.getClass().getSimpleName() : m;
  }

  @JavascriptInterface
  public String poll(String id) {
    Job j = jobs.get(id);
    JSONObject o = new JSONObject();
    try {
      if (j == null) { o.put("state", "error"); o.put("error", "Unknown job"); return o.toString(); }
      o.put("state", j.state);
      o.put("stage", j.stage);
      o.put("pct", j.pct);
      o.put("eta", j.eta);
      o.put("error", j.error == null ? JSONObject.NULL : j.error);
      o.put("uri", j.uri == null ? JSONObject.NULL : j.uri);
      o.put("name", j.name == null ? JSONObject.NULL : j.name);
      o.put("mime", j.mime == null ? JSONObject.NULL : j.mime);
      o.put("size", j.size);
    } catch (Exception ignore) {}
    return o.toString();
  }

  @JavascriptInterface
  public void cancel(String id) {
    Job j = jobs.get(id);
    if (j != null) j.cancelled = true;
  }

  /* ---------- model files ---------- */
  private File[] modelFiles() throws Exception {
    File dir = new File(ctx.getFilesDir(), "ai");
    dir.mkdirs();
    File[] r = new File[2];
    String[] ext = {".param", ".bin"};
    for (int i = 0; i < 2; i++) {
      File f = new File(dir, MODEL + ext[i]);
      try (InputStream in = ctx.getAssets().open("ai/" + MODEL + ext[i])) {
        long have = f.exists() ? f.length() : -1;
        if (have <= 0 || have != in.available()) {
          try (OutputStream out = new FileOutputStream(f)) {
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
          }
        }
      }
      r[i] = f;
    }
    return r;
  }

  /* ---------- the video pipeline ---------- */
  private static int roundTo(int v, int a) {
    a = Math.max(2, a);
    return Math.max(a, Math.round(v / (float) a) * a);
  }

  private static void checkCancel(Job j) throws Cancelled {
    if (j.cancelled) throw new Cancelled();
  }

  // colour conversion settings of the source
  private static final class Colour {
    boolean full;       // full range (0-255) instead of limited (16-235)
    boolean bt601;      // BT.601 matrix instead of BT.709
  }

  private void run(Job j, Uri src, JSONObject opts, File outFile) throws Exception {
    if (!libOk) throw new Exception("The AI engine isn’t available in this build (" + libErr + ")");
    if (!hasModel()) throw new Exception("The AI model is missing from this build");

    j.stage = "Starting the AI…";
    File[] mf = modelFiles();
    int tile = 128;   // small pieces keep the phone's screen responsive
    int rc = nInit(mf[0].getAbsolutePath(), mf[1].getAbsolutePath(), MODEL_SCALE, tile);
    if (rc == -1) { initErr = "no Vulkan"; throw new Exception("This phone’s graphics chip doesn’t support Vulkan, so the AI can’t run here."); }
    if (rc != 0) { initErr = "model " + rc; throw new Exception("Couldn’t load the AI model (" + rc + ")"); }
    gpuName = nGpu();

    MediaExtractor vx = new MediaExtractor();
    MediaExtractor ax = null;
    MediaCodec dec = null, enc = null;
    MediaMuxer mm = null;
    final Mx mx = new Mx();
    try {
      vx.setDataSource(ctx, src, null);
      int vt = -1;
      for (int i = 0; i < vx.getTrackCount(); i++) {
        String mime = vx.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
        if (mime != null && mime.startsWith("video/")) { vt = i; break; }
      }
      if (vt < 0) throw new Exception("No video found in that file");
      vx.selectTrack(vt);
      MediaFormat vf = vx.getTrackFormat(vt);
      final String vmime = vf.getString(MediaFormat.KEY_MIME);
      final int inW = vf.getInteger(MediaFormat.KEY_WIDTH), inH = vf.getInteger(MediaFormat.KEY_HEIGHT);
      int rot = vf.containsKey(MediaFormat.KEY_ROTATION) ? vf.getInteger(MediaFormat.KEY_ROTATION) : 0;
      long durUs = vf.containsKey(MediaFormat.KEY_DURATION) ? vf.getLong(MediaFormat.KEY_DURATION) : 0;
      int fps = 30;
      try {
        if (vf.containsKey(MediaFormat.KEY_FRAME_RATE)) {
          try { fps = vf.getInteger(MediaFormat.KEY_FRAME_RATE); } catch (Exception e) { fps = Math.round(vf.getFloat(MediaFormat.KEY_FRAME_RATE)); }
        }
      } catch (Exception ignore) {}
      if (fps < 5 || fps > 120) fps = 30;

      // ----- sizes -----
      final int shortSide = Math.min(inW, inH);
      final int target = opts.optInt("target", 1080);
      if (target <= shortSide) throw new Exception("This video is already " + shortSide + "p. Choose a bigger size.");
      double f = (double) target / shortSide;
      int outW = roundTo((int) Math.round(inW * f), 2), outH = roundTo((int) Math.round(inH * f), 2);
      double mbps = opts.optDouble("bitrate", 0);
      int bitrate = mbps > 0 ? (int) (mbps * 1_000_000) : (int) Math.max(8_000_000L, Math.min(50_000_000L, (long) (outW * (double) outH * fps * 0.2)));

      // ----- find an H.264 encoder that can do this size, and align the size to what it wants -----
      MediaFormat probe = MediaFormat.createVideoFormat("video/avc", outW, outH);
      probe.setInteger(MediaFormat.KEY_COLOR_FORMAT, COLOR_FLEX);
      probe.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
      probe.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
      String encName = new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(probe);
      if (encName == null) throw new Exception("This phone’s video encoder can’t make " + outW + "×" + outH + ". Try a smaller size.");
      MediaCodecInfo.VideoCapabilities vc = null;
      for (MediaCodecInfo ci : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
        if (ci.getName().equals(encName)) { vc = ci.getCapabilitiesForType("video/avc").getVideoCapabilities(); break; }
      }
      if (vc != null) {
        outW = roundTo(outW, vc.getWidthAlignment());
        outH = roundTo(outH, vc.getHeightAlignment());
        if (!vc.isSizeSupported(outW, outH)) throw new Exception("This phone’s video encoder can’t make " + outW + "×" + outH + ". Try a smaller size.");
      }
      final int OW = outW, OH = outH;

      MediaFormat ef = MediaFormat.createVideoFormat("video/avc", OW, OH);
      ef.setInteger(MediaFormat.KEY_COLOR_FORMAT, COLOR_FLEX);
      ef.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
      ef.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
      ef.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
      if (Build.VERSION.SDK_INT >= 24) {
        ef.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
        ef.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
        ef.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
      }
      enc = MediaCodec.createByCodecName(encName);
      enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
      enc.start();

      vf.setInteger(MediaFormat.KEY_COLOR_FORMAT, COLOR_FLEX);
      dec = MediaCodec.createDecoderByType(vmime);
      dec.configure(vf, null, null, 0);
      dec.start();

      // ----- the sound (copied as it is) -----
      int at = -1;
      MediaFormat af = null;
      ax = new MediaExtractor();
      ax.setDataSource(ctx, src, null);
      for (int i = 0; i < ax.getTrackCount(); i++) {
        String mime = ax.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
        if (mime != null && mime.startsWith("audio/")) { at = i; af = ax.getTrackFormat(i); break; }
      }
      if (at >= 0) ax.selectTrack(at); else { ax.release(); ax = null; }

      mm = new MediaMuxer(outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
      if (rot == 90 || rot == 180 || rot == 270) mm.setOrientationHint(rot);
      mx.mm = mm; mx.ax = ax; mx.af = af;

      // ----- buffers -----
      final int aiW = inW * MODEL_SCALE, aiH = inH * MODEL_SCALE;
      int[] argbIn = new int[inW * inH];
      int[] argbAi = new int[aiW * aiH];
      boolean needResize = aiW != OW || aiH != OH;
      Bitmap bmAi = null, bmOut = null;
      int[] argbOut = argbAi;
      Canvas canvas = null;
      Paint paint = null;
      Rect rSrc = null, rDst = null;
      if (needResize) {
        bmAi = Bitmap.createBitmap(aiW, aiH, Bitmap.Config.ARGB_8888);
        bmOut = Bitmap.createBitmap(OW, OH, Bitmap.Config.ARGB_8888);
        argbOut = new int[OW * OH];
        canvas = new Canvas(bmOut);
        paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        rSrc = new Rect(0, 0, aiW, aiH);
        rDst = new Rect(0, 0, OW, OH);
      }
      byte[][] planeCache = new byte[3][];

      MediaCodec.BufferInfo di = new MediaCodec.BufferInfo(), ei = new MediaCodec.BufferInfo();
      Colour colour = new Colour();
      boolean inDone = false, decDone = false, encDone = false;
      long frames = 0;
      final long total = Math.max(1, durUs > 0 ? (long) (durUs / 1_000_000.0 * fps) : 300);
      long t0 = System.currentTimeMillis();

      while (!encDone) {
        checkCancel(j);

        // 1. feed the decoder
        if (!inDone) {
          int ix = dec.dequeueInputBuffer(0);
          if (ix >= 0) {
            ByteBuffer b = dec.getInputBuffer(ix);
            int n = vx.readSampleData(b, 0);
            if (n < 0) {
              dec.queueInputBuffer(ix, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
              inDone = true;
            } else {
              dec.queueInputBuffer(ix, 0, n, vx.getSampleTime(), 0);
              vx.advance();
            }
          }
        }

        // 2. collect what the encoder has finished (also starts the muxer when the encoder reports its format)
        if (mx.drain(enc, ei, decDone ? 10_000 : 0)) { encDone = true; break; }

        // 3. take one decoded picture, enhance it, give it to the encoder
        if (!decDone) {
          int ox = dec.dequeueOutputBuffer(di, 10_000);
          if (ox == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            MediaFormat of = dec.getOutputFormat();
            if (Build.VERSION.SDK_INT >= 24) {
              if (of.containsKey(MediaFormat.KEY_COLOR_RANGE)) colour.full = of.getInteger(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL;
              if (of.containsKey(MediaFormat.KEY_COLOR_STANDARD)) colour.bt601 = of.getInteger(MediaFormat.KEY_COLOR_STANDARD) == MediaFormat.COLOR_STANDARD_BT601_PAL
                  || of.getInteger(MediaFormat.KEY_COLOR_STANDARD) == MediaFormat.COLOR_STANDARD_BT601_NTSC;
            }
          } else if (ox >= 0) {
            final boolean eos = (di.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            if (di.size > 0) {
              Image img = dec.getOutputImage(ox);
              if (img == null) { dec.releaseOutputBuffer(ox, false); throw new Exception("This phone’s decoder gave a picture format I can’t read"); }
              final long fs = System.currentTimeMillis();
              toArgb(img, inW, inH, argbIn, colour, planeCache);
              dec.releaseOutputBuffer(ox, false);

              int r = nUpscale(argbIn, inW, inH, argbAi);
              if (r == -12) {           // the GPU ran out of memory: use smaller pieces and try again
                nSetTile(128);
                r = nUpscale(argbIn, inW, inH, argbAi);
              }
              if (r != 0) throw new Exception("The AI failed on this phone (code " + r + ")");

              int[] pix = argbAi;
              if (needResize) {
                bmAi.setPixels(argbAi, 0, aiW, 0, 0, aiW, aiH);
                canvas.drawBitmap(bmAi, rSrc, rDst, paint);
                bmOut.getPixels(argbOut, 0, OW, 0, 0, OW, OH);
                pix = argbOut;
              }
              int ei2 = -1;
              long wait = System.currentTimeMillis();
              while (ei2 < 0) {
                checkCancel(j);
                ei2 = enc.dequeueInputBuffer(10_000);
                if (ei2 < 0) {
                  if (mx.drain(enc, ei, 0)) { encDone = true; }
                  if (System.currentTimeMillis() - wait > 30_000) throw new Exception("The video encoder stopped responding");
                }
              }
              Image eimg = enc.getInputImage(ei2);
              if (eimg == null) throw new Exception("This phone’s encoder can’t take pictures this way");
              fillYuv(eimg, pix, OW, OH);
              enc.queueInputBuffer(ei2, 0, OW * OH * 3 / 2, di.presentationTimeUs, 0);

              frames++;
              long el = System.currentTimeMillis() - t0;
              j.pct = (int) Math.min(99, frames * 100 / total);
              j.eta = Math.max(0, (long) (el / (double) frames * Math.max(0, total - frames) / 1000.0));
              j.stage = "Enhancing frame " + frames + " of ~" + total;
              // give the GPU a rest so the phone does not freeze
              try { Thread.sleep(Math.min(1500, Math.max(60, (System.currentTimeMillis() - fs) / 2))); } catch (InterruptedException ie) { throw new Cancelled(); }
            } else {
              dec.releaseOutputBuffer(ox, false);
            }
            if (eos) {
              decDone = true;
              int ix = -1;
              while (ix < 0) {
                checkCancel(j);
                ix = enc.dequeueInputBuffer(10_000);
                if (ix < 0) if (mx.drain(enc, ei, 0)) { encDone = true; }
              }
              enc.queueInputBuffer(ix, 0, 0, di.presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
          }
        }
      }
      if (frames == 0) throw new Exception("No pictures could be read from that video");
    } finally {
      if (dec != null) { try { dec.stop(); } catch (Exception ignore) {} try { dec.release(); } catch (Exception ignore) {} }
      if (enc != null) { try { enc.stop(); } catch (Exception ignore) {} try { enc.release(); } catch (Exception ignore) {} }
      if (mm != null) {
        try { if (mx.started) mm.stop(); } catch (Exception ignore) {}
        try { mm.release(); } catch (Exception ignore) {}
      }
      vx.release();
      if (ax != null) ax.release();
    }
  }

  /** The muxer and everything that has to happen around starting it. */
  private final class Mx {
    MediaMuxer mm;
    MediaExtractor ax;
    MediaFormat af;
    int vm = -1;
    boolean started;

    /** Writes what the encoder has finished. Returns true once the encoder has said it is at the end. */
    boolean drain(MediaCodec enc, MediaCodec.BufferInfo ei, long timeoutUs) {
      boolean eos = false;
      int ox = enc.dequeueOutputBuffer(ei, timeoutUs);
      while (ox != MediaCodec.INFO_TRY_AGAIN_LATER) {
        if (ox == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          vm = mm.addTrack(enc.getOutputFormat());
          int am = -1;
          if (ax != null) { try { am = mm.addTrack(af); } catch (Exception e) { am = -1; } }
          mm.start();
          started = true;
          if (am >= 0) copyAudio(ax, mm, am);
        } else if (ox >= 0) {
          ByteBuffer ob = enc.getOutputBuffer(ox);
          if ((ei.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) ei.size = 0;
          if (ei.size > 0 && started) {
            ob.position(ei.offset);
            ob.limit(ei.offset + ei.size);
            mm.writeSampleData(vm, ob, ei);
          }
          enc.releaseOutputBuffer(ox, false);
          if ((ei.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) { eos = true; break; }
        }
        ox = enc.dequeueOutputBuffer(ei, 0);
      }
      return eos;
    }
  }

  private void copyAudio(MediaExtractor ax, MediaMuxer mm, int track) {
    try {
      ByteBuffer buf = ByteBuffer.allocate(1 << 20);
      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      ax.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
      while (true) {
        buf.clear();
        int n = ax.readSampleData(buf, 0);
        if (n < 0) break;
        info.set(0, n, ax.getSampleTime(), (ax.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
        mm.writeSampleData(track, buf, info);
        ax.advance();
      }
    } catch (Exception ignore) {}   // a failure here only costs the sound
  }

  /* ---------- colour conversion ---------- */
  private static byte[] plane(Image.Plane p, byte[][] cache, int idx) {
    ByteBuffer b = p.getBuffer();
    int n = b.remaining();
    if (cache[idx] == null || cache[idx].length < n) cache[idx] = new byte[n];
    b.get(cache[idx], 0, n);
    b.rewind();
    return cache[idx];
  }

  private static int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }

  private static void toArgb(Image img, int w, int h, int[] out, Colour c, byte[][] cache) throws Exception {
    Rect crop = img.getCropRect();
    if (crop.width() != w || crop.height() != h) throw new Exception("Unsupported video size (" + crop.width() + "×" + crop.height() + ")");
    Image.Plane[] pl = img.getPlanes();
    byte[] ya = plane(pl[0], cache, 0), ua = plane(pl[1], cache, 1), va = plane(pl[2], cache, 2);
    final int yrs = pl[0].getRowStride(), yps = pl[0].getPixelStride();
    final int urs = pl[1].getRowStride(), ups = pl[1].getPixelStride();
    final int vrs = pl[2].getRowStride(), vps = pl[2].getPixelStride();
    final int cx = crop.left, cy = crop.top;

    // 8-bit fixed point (x256)
    final int ky, yoff, rv, gu, gv, bu;
    if (c.full) {
      ky = 256; yoff = 0;
      if (c.bt601) { rv = 359; gu = 88; gv = 183; bu = 454; } else { rv = 403; gu = 48; gv = 120; bu = 475; }
    } else {
      ky = 298; yoff = 16;
      if (c.bt601) { rv = 409; gu = 100; gv = 208; bu = 516; } else { rv = 459; gu = 55; gv = 136; bu = 541; }
    }
    int o = 0;
    for (int y = 0; y < h; y++) {
      final int yRow = (cy + y) * yrs;
      final int uRow = ((cy + y) >> 1) * urs;
      final int vRow = ((cy + y) >> 1) * vrs;
      for (int x = 0; x < w; x++) {
        final int xx = cx + x;
        int Y = (ya[yRow + xx * yps] & 0xff) - yoff;
        int U = (ua[uRow + (xx >> 1) * ups] & 0xff) - 128;
        int V = (va[vRow + (xx >> 1) * vps] & 0xff) - 128;
        if (Y < 0) Y = 0;
        int yy = ky * Y + 128;
        int r = clamp((yy + rv * V) >> 8);
        int g = clamp((yy - gu * U - gv * V) >> 8);
        int b = clamp((yy + bu * U) >> 8);
        out[o++] = 0xff000000 | (r << 16) | (g << 8) | b;
      }
    }
  }

  /** ARGB pixels -> the encoder's YUV 4:2:0 input image (BT.709, limited range). */
  private static void fillYuv(Image img, int[] argb, int w, int h) {
    Image.Plane[] pl = img.getPlanes();
    ByteBuffer yb = pl[0].getBuffer(), ub = pl[1].getBuffer(), vb = pl[2].getBuffer();
    final int yrs = pl[0].getRowStride(), yps = pl[0].getPixelStride();
    final int urs = pl[1].getRowStride(), ups = pl[1].getPixelStride();
    final int vrs = pl[2].getRowStride(), vps = pl[2].getPixelStride();
    byte[] row = new byte[w];
    for (int y = 0; y < h; y++) {
      int o = y * w;
      for (int x = 0; x < w; x++) {
        int p = argb[o + x];
        int r = (p >> 16) & 0xff, g = (p >> 8) & 0xff, b = p & 0xff;
        row[x] = (byte) (((47 * r + 157 * g + 16 * b + 128) >> 8) + 16);
      }
      if (yps == 1) { yb.position(y * yrs); yb.put(row, 0, w); }
      else for (int x = 0; x < w; x++) yb.put(y * yrs + x * yps, row[x]);
    }
    final int cw = w / 2, ch = h / 2;
    byte[] ur = new byte[cw], vr = new byte[cw];
    for (int cyy = 0; cyy < ch; cyy++) {
      for (int cxx = 0; cxx < cw; cxx++) {
        int i0 = (cyy * 2) * w + cxx * 2, i1 = i0 + w;
        int p0 = argb[i0], p1 = argb[i0 + 1], p2 = argb[i1], p3 = argb[i1 + 1];
        int r = (((p0 >> 16) & 0xff) + ((p1 >> 16) & 0xff) + ((p2 >> 16) & 0xff) + ((p3 >> 16) & 0xff) + 2) >> 2;
        int g = (((p0 >> 8) & 0xff) + ((p1 >> 8) & 0xff) + ((p2 >> 8) & 0xff) + ((p3 >> 8) & 0xff) + 2) >> 2;
        int b = ((p0 & 0xff) + (p1 & 0xff) + (p2 & 0xff) + (p3 & 0xff) + 2) >> 2;
        ur[cxx] = (byte) clamp(((-26 * r - 87 * g + 112 * b + 128) >> 8) + 128);
        vr[cxx] = (byte) clamp(((112 * r - 102 * g - 10 * b + 128) >> 8) + 128);
      }
      if (ups == 1) { ub.position(cyy * urs); ub.put(ur, 0, cw); }
      else for (int x = 0; x < cw; x++) ub.put(cyy * urs + x * ups, ur[x]);
      if (vps == 1) { vb.position(cyy * vrs); vb.put(vr, 0, cw); }
      else for (int x = 0; x < cw; x++) vb.put(cyy * vrs + x * vps, vr[x]);
    }
  }

  /* ---------- save into Downloads ---------- */
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
    try (InputStream in = new FileInputStream(f); OutputStream out = new FileOutputStream(dst)) {
      byte[] buf = new byte[1 << 16];
      int n;
      while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
    return androidx.core.content.FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", dst);
  }
}
