package com.clipgrab.app;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.nio.ByteBuffer;

/**
 * Merges separate video + audio files and/or trims a time range using Android's own MediaExtractor / MediaMuxer.
 * Used as the backup system when ffmpeg can't run on the phone. No re-encoding: it copies the compressed samples.
 * (A cut starts at the keyframe just before the requested start, so a clip can begin up to a few seconds early.)
 */
public final class Mux {
  private Mux() {}

  private static int trackOf(MediaExtractor ex, String prefix) {
    int n = ex.getTrackCount();
    for (int i = 0; i < n; i++) {
      String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
      if (mime != null && mime.startsWith(prefix)) return i;
    }
    return -1;
  }

  /** prefix is "video/" or "audio/". */
  public static boolean hasTrack(String path, String prefix) throws Exception {
    MediaExtractor ex = new MediaExtractor();
    try {
      ex.setDataSource(path);
      return trackOf(ex, prefix) >= 0;
    } finally {
      ex.release();
    }
  }

  private static int bufSize(MediaFormat f) {
    int n = 0;
    if (f != null && f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) n = f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
    return Math.max(n, 6 << 20);
  }

  /**
   * videoPath may be null (audio only). audioPath may be null (then the sound inside videoPath, if any, is kept).
   * startUs / endUs in microseconds; endUs <= 0 means "to the end".
   */
  public static void mux(String videoPath, String audioPath, String outPath, long startUs, long endUs, boolean webm) throws Exception {
    if (videoPath == null && audioPath == null) throw new Exception("Nothing to merge");
    MediaExtractor vx = null, ax = null;
    MediaMuxer mm = null;
    boolean started = false;
    try {
      int vt = -1, at = -1;
      MediaFormat vf = null, af = null;
      if (videoPath != null) {
        vx = new MediaExtractor();
        vx.setDataSource(videoPath);
        vt = trackOf(vx, "video/");
        if (vt < 0) throw new Exception("The downloaded file has no video");
        vf = vx.getTrackFormat(vt);
      }
      String audioSrc = audioPath != null ? audioPath : videoPath;
      ax = new MediaExtractor();
      ax.setDataSource(audioSrc);
      at = trackOf(ax, "audio/");
      if (at < 0) {
        if (audioPath != null || vx == null) throw new Exception("The downloaded audio has no sound");
        ax.release();
        ax = null;   // a video without sound stays without sound
      } else {
        af = ax.getTrackFormat(at);
      }

      mm = new MediaMuxer(outPath, webm ? MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM : MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
      int vm = -1, am = -1;
      if (vx != null) {
        vm = mm.addTrack(vf);
        if (!webm && vf.containsKey(MediaFormat.KEY_ROTATION)) {
          int r = vf.getInteger(MediaFormat.KEY_ROTATION);
          if (r == 0 || r == 90 || r == 180 || r == 270) mm.setOrientationHint(r);
        }
        vx.selectTrack(vt);
      }
      if (ax != null) {
        am = mm.addTrack(af);
        ax.selectTrack(at);
      }
      long base;
      if (startUs > 0) {
        if (vx != null) {
          vx.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);   // video can only start on a keyframe...
          base = vx.getSampleTime();
          if (ax != null) ax.seekTo(Math.max(0L, base), MediaExtractor.SEEK_TO_PREVIOUS_SYNC);   // ...so the sound starts there too
        } else {
          ax.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
          base = ax.getSampleTime();
        }
      } else {
        base = vx != null ? vx.getSampleTime() : ax.getSampleTime();
      }
      if (base < 0) base = 0;

      ByteBuffer vbuf = vx != null ? ByteBuffer.allocate(bufSize(vf)) : null;
      ByteBuffer abuf = ax != null ? ByteBuffer.allocate(bufSize(af)) : null;
      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

      mm.start();
      started = true;

      boolean vDone = vx == null, aDone = ax == null;
      long written = 0;
      while (!vDone || !aDone) {
        long vtm = vDone ? Long.MAX_VALUE : vx.getSampleTime();
        long atm = aDone ? Long.MAX_VALUE : ax.getSampleTime();
        if (!vDone && vtm < 0) { vDone = true; continue; }
        if (!aDone && atm < 0) { aDone = true; continue; }
        boolean useV = !vDone && (aDone || vtm <= atm);
        MediaExtractor ex = useV ? vx : ax;
        ByteBuffer buf = useV ? vbuf : abuf;
        int outTrack = useV ? vm : am;
        long t = useV ? vtm : atm;
        if (endUs > 0 && t > endUs) {
          if (useV) vDone = true; else aDone = true;
          continue;
        }
        if (!useV && t < base) {   // sound that comes before the first video keyframe
          ex.advance();
          continue;
        }
        buf.clear();
        int size = ex.readSampleData(buf, 0);
        if (size < 0) {
          if (useV) vDone = true; else aDone = true;
          continue;
        }
        int flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_SYNC_FRAME : 0;
        info.set(0, size, Math.max(0L, t - base), flags);
        mm.writeSampleData(outTrack, buf, info);
        written++;
        ex.advance();
      }
      if (written == 0) throw new Exception("Nothing to save in that time range");
    } finally {
      if (mm != null) {
        try { if (started) mm.stop(); } catch (Exception ignore) {}
        try { mm.release(); } catch (Exception ignore) {}
      }
      if (vx != null) vx.release();
      if (ax != null) ax.release();
    }
  }
}
