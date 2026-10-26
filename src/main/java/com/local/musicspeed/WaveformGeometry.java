package com.local.musicspeed;

/**
 * Pure geometry for the continuous (whole-track) waveform strip.
 *
 * <p>The reference player lays the whole track out as one long strip and scrolls it
 * horizontally. This class keeps that mapping free of Android types so it can be unit
 * tested on the JVM:</p>
 *
 * <pre>
 *   strip width  = durationSeconds × dpPerSecond × density   (capped, see MAX_WAVE_WIDTH_PX)
 *   content x    = viewportWidth / 2 + ratio × stripWidth
 *   scroll x     = ratio × stripWidth                        (0 .. stripWidth)
 *   centre time  = scrollX / stripWidth × duration
 * </pre>
 *
 * <p>Because the left/right gutters are half a viewport each, the playhead always sits in
 * the middle of the panel and the first/last sample can reach it. At {@code scrollX = 0} the
 * track start is under the playhead; at {@code scrollX = stripWidth} the track end is.</p>
 */
public final class WaveformGeometry {

    /** Whole-track zoom levels in dp per second (0.5x, 1x, 2x of the reference 5 dp/s). */
    public static final float[] ZOOM_DP_PER_SECOND = {2.5f, 5f, 10f};

    /** 1.6 starts at 0.5x (2.5 dp/s) so a 3-minute track is closer to one screen wide. */
    public static final int DEFAULT_ZOOM_INDEX = 0;

    /** Bar geometry borrowed from the reference player: 2 dp bar, 2 dp gap, 1 dp radius. */
    public static final float BAR_WIDTH_DP = 2f;
    public static final float BAR_GAP_DP = 2f;
    public static final float BAR_RADIUS_DP = 1f;

    /** The reference player fills 85% of the panel height with bars. */
    public static final float CHUNK_HEIGHT_RATIO = 0.85f;

    /**
     * Hard cap for the strip width in pixels. A 10-minute track at 10 dp/s on a 3x screen
     * would otherwise need ~180,000 px; the cap keeps the scroll/precision maths and the
     * visible (time → bar) mapping bounded without any large allocation.
     */
    public static final int MAX_WAVE_WIDTH_PX = 8_192;

    private static final long[] RULER_STEPS_SECONDS = {
            1L, 2L, 5L, 10L, 15L, 30L, 60L, 120L, 300L, 600L, 900L, 1_800L
    };

    private WaveformGeometry() {
    }

    /** Wraps (and normalises) a zoom index into {@link #ZOOM_DP_PER_SECOND}. */
    public static int wrapZoomIndex(int index) {
        int length = ZOOM_DP_PER_SECOND.length;
        return ((index % length) + length) % length;
    }

    public static float dpPerSecond(int zoomIndex) {
        return ZOOM_DP_PER_SECOND[wrapZoomIndex(zoomIndex)];
    }

    /** Strip width in pixels, capped at {@link #MAX_WAVE_WIDTH_PX}. */
    public static float waveWidthPx(long durationMs, float dpPerSecond, float density) {
        if (durationMs <= 0L || dpPerSecond <= 0f || density <= 0f) {
            return 0f;
        }
        double width = durationMs / 1000.0 * dpPerSecond * density;
        if (!(width > 0.0)) {
            return 0f;
        }
        return (float) Math.min((double) MAX_WAVE_WIDTH_PX, width);
    }

    /** Width of one bar plus its gap, in pixels. */
    public static float barStepPx(float density) {
        return Math.max(1f, (BAR_WIDTH_DP + BAR_GAP_DP) * Math.max(0f, density));
    }

    /** Number of bars that fit on the strip (at least 1 when there is any width). */
    public static int barCount(float waveWidthPx, float density) {
        if (waveWidthPx <= 0f) {
            return 0;
        }
        int count = (int) Math.floor(waveWidthPx / barStepPx(density));
        return Math.max(1, count);
    }

    /** Scroll offset (px) that puts {@code timeMs} under the playhead. */
    public static float scrollForTime(long timeMs, long durationMs, float waveWidthPx) {
        if (durationMs <= 0L || waveWidthPx <= 0f) {
            return 0f;
        }
        double ratio = (double) timeMs / (double) durationMs;
        if (ratio <= 0.0) {
            return 0f;
        }
        if (ratio >= 1.0) {
            return waveWidthPx;
        }
        return clampScroll((float) (ratio * waveWidthPx), waveWidthPx);
    }

    /** Track time under the playhead for a given scroll offset. */
    public static long centerTimeMs(float scrollPx, long durationMs, float waveWidthPx) {
        if (durationMs <= 0L || waveWidthPx <= 0f) {
            return 0L;
        }
        double ratio = clamp01(scrollPx / waveWidthPx);
        return clampTime(Math.round(ratio * durationMs), durationMs);
    }

    /** Track time under an arbitrary screen x inside the drawing panel. */
    public static long timeForScreenX(float screenX, float paddingLeft, float viewportWidth,
                                      float scrollPx, long durationMs, float waveWidthPx) {
        if (durationMs <= 0L || waveWidthPx <= 0f || viewportWidth <= 0f) {
            return 0L;
        }
        float edge = viewportWidth / 2f;
        double contentX = (double) scrollPx + ((double) screenX - paddingLeft);
        double ratio = (contentX - edge) / waveWidthPx;
        return clampTime(Math.round(clamp01(ratio) * durationMs), durationMs);
    }

    /** Screen x (inside the view, including padding) where {@code timeMs} is drawn. */
    public static float screenXForTime(long timeMs, float paddingLeft, float viewportWidth,
                                       float scrollPx, long durationMs, float waveWidthPx) {
        if (durationMs <= 0L || waveWidthPx <= 0f) {
            return paddingLeft;
        }
        float edge = viewportWidth / 2f;
        double ratio = clamp01((double) timeMs / (double) durationMs);
        return (float) (paddingLeft + edge + ratio * waveWidthPx - scrollPx);
    }

    /** Screen x of the fixed playhead (centre of the drawing panel). */
    public static float playheadX(float paddingLeft, float viewportWidth) {
        return paddingLeft + viewportWidth / 2f;
    }

    /** Scroll offsets always live in {@code 0 .. waveWidthPx}. */
    public static float clampScroll(float candidate, float waveWidthPx) {
        if (!(candidate > 0f)) {
            return 0f;
        }
        return Math.min(Math.max(0f, waveWidthPx), candidate);
    }

    /** Time at the left edge of the visible panel. */
    public static long visibleStartMs(float paddingLeft, float viewportWidth, float scrollPx,
                                      long durationMs, float waveWidthPx) {
        return timeForScreenX(paddingLeft, paddingLeft, viewportWidth, scrollPx, durationMs,
                waveWidthPx);
    }

    /** Time at the right edge of the visible panel. */
    public static long visibleEndMs(float paddingLeft, float viewportWidth, float scrollPx,
                                    long durationMs, float waveWidthPx) {
        return timeForScreenX(paddingLeft + viewportWidth, paddingLeft, viewportWidth, scrollPx,
                durationMs, waveWidthPx);
    }

    /**
     * Ruler interval: the smallest step whose on-screen length is at least
     * {@code minLabelPx}, so labels never overlap. Falls back to the largest step.
     */
    public static long rulerStepSeconds(float pxPerSecond, float minLabelPx) {
        if (!(pxPerSecond > 0f)) {
            return RULER_STEPS_SECONDS[RULER_STEPS_SECONDS.length - 1];
        }
        for (long step : RULER_STEPS_SECONDS) {
            if (step * pxPerSecond >= minLabelPx) {
                return step;
            }
        }
        return RULER_STEPS_SECONDS[RULER_STEPS_SECONDS.length - 1];
    }

    /** Ruler interval in milliseconds (the view's tick loop works in ms). */
    public static long rulerStepMs(float pxPerSecond, float minLabelPx) {
        return Math.max(1000L, rulerStepSeconds(pxPerSecond, minLabelPx) * 1000L);
    }

    public static long clampTime(long timeMs, long durationMs) {
        if (durationMs <= 0L) {
            return 0L;
        }
        return Math.max(0L, Math.min(durationMs, timeMs));
    }

    /**
     * Estimate of the playback position between two 200 ms ticker updates.
     *
     * @param lastPositionMs position reported by the last ticker callback
     * @param lastUptimeMs   {@code SystemClock.uptimeMillis()} captured with that callback
     * @param speed          current playback speed multiplier (0.25 .. 4.0)
     * @param nowUptimeMs    current uptime
     * @param durationMs     track duration used to stop at the end; 0 = unknown (no clamp)
     */
    public static long interpolatedPosition(long lastPositionMs, long lastUptimeMs,
                                            float speed, long nowUptimeMs, long durationMs) {
        long elapsed = Math.max(0L, nowUptimeMs - lastUptimeMs);
        float safeSpeed = speed > 0f && !Float.isNaN(speed) ? Math.min(8f, speed) : 1f;
        long advanced = (long) (elapsed * safeSpeed);
        long position = Math.max(0L, lastPositionMs) + advanced;
        if (durationMs > 0L) {
            position = Math.min(durationMs, position);
        }
        return position;
    }

    private static double clamp01(double value) {
        if (!(value > 0.0)) {
            return 0.0;
        }
        return Math.min(1.0, value);
    }
}
