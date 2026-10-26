package com.local.musicspeed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * JVM tests for the continuous-strip geometry and the 200 ms interpolation.
 */
public class WaveformGeometryTest {

    private static final float EPS = 0.01f;

    @Test
    public void waveWidthScalesWithDurationZoomAndDensity() {
        assertEquals(400f, WaveformGeometry.waveWidthPx(40_000L, 5f, 2f), EPS);
        assertEquals(800f, WaveformGeometry.waveWidthPx(40_000L, 10f, 2f), EPS);
        assertEquals(200f, WaveformGeometry.waveWidthPx(40_000L, 5f, 1f), EPS);
        assertEquals(0f, WaveformGeometry.waveWidthPx(0L, 5f, 2f), EPS);
        assertEquals(0f, WaveformGeometry.waveWidthPx(40_000L, 0f, 2f), EPS);
    }

    @Test
    public void waveWidthIsCappedForVeryLongTracks() {
        float width = WaveformGeometry.waveWidthPx(3_600_000L, 10f, 3f);
        assertEquals(WaveformGeometry.MAX_WAVE_WIDTH_PX, width, 0.001f);
    }

    @Test
    public void barCountUsesTwoDpBarAndTwoDpGap() {
        // 4 dp per bar at density 2 = 8 px.
        assertEquals(50, WaveformGeometry.barCount(400f, 2f));
        assertEquals(1, WaveformGeometry.barCount(7f, 2f));
        assertEquals(0, WaveformGeometry.barCount(0f, 2f));
    }

    @Test
    public void defaultZoomIsHalfSpeed() {
        assertEquals(0, WaveformGeometry.DEFAULT_ZOOM_INDEX);
        assertEquals(2.5f, WaveformGeometry.dpPerSecond(WaveformGeometry.DEFAULT_ZOOM_INDEX),
                EPS);
        // The view's labels are {"0.5", "1", "2"} in the same order.
        assertEquals(5f, WaveformGeometry.dpPerSecond(WaveformGeometry.DEFAULT_ZOOM_INDEX + 1),
                EPS);
        assertEquals(10f, WaveformGeometry.dpPerSecond(WaveformGeometry.DEFAULT_ZOOM_INDEX + 2),
                EPS);
    }

    @Test
    public void zoomLevelsCycleAndWrap() {
        assertEquals(2.5f, WaveformGeometry.dpPerSecond(0), EPS);
        assertEquals(5f, WaveformGeometry.dpPerSecond(1), EPS);
        assertEquals(10f, WaveformGeometry.dpPerSecond(2), EPS);
        assertEquals(5f, WaveformGeometry.dpPerSecond(4), EPS);
        assertEquals(10f, WaveformGeometry.dpPerSecond(-1), EPS);
        assertEquals(1, WaveformGeometry.wrapZoomIndex(4));
        assertEquals(2, WaveformGeometry.wrapZoomIndex(-1));
    }

    @Test
    public void scrollForTimeSpansTheWholeStrip() {
        assertEquals(0f, WaveformGeometry.scrollForTime(0L, 100_000L, 4_000f), EPS);
        assertEquals(2_000f, WaveformGeometry.scrollForTime(50_000L, 100_000L, 4_000f), EPS);
        assertEquals(4_000f, WaveformGeometry.scrollForTime(100_000L, 100_000L, 4_000f), EPS);
        assertEquals(0f, WaveformGeometry.scrollForTime(-1_000L, 100_000L, 4_000f), EPS);
        assertEquals(4_000f, WaveformGeometry.scrollForTime(200_000L, 100_000L, 4_000f), EPS);
    }

    @Test
    public void centerTimeMatchesScroll() {
        assertEquals(0L, WaveformGeometry.centerTimeMs(0f, 100_000L, 4_000f));
        assertEquals(50_000L, WaveformGeometry.centerTimeMs(2_000f, 100_000L, 4_000f));
        assertEquals(100_000L, WaveformGeometry.centerTimeMs(4_000f, 100_000L, 4_000f));
        assertEquals(0L, WaveformGeometry.centerTimeMs(0f, 0L, 4_000f));
    }

    @Test
    public void screenMappingIsInverseAtThePlayhead() {
        float paddingLeft = 10f;
        float viewport = 1_000f;
        float scroll = 1_234f;
        long duration = 120_000L;
        float waveWidth = 4_000f;

        long center = WaveformGeometry.centerTimeMs(scroll, duration, waveWidth);
        float playhead = WaveformGeometry.playheadX(paddingLeft, viewport);
        assertEquals(510f, playhead, EPS);
        assertEquals(playhead, WaveformGeometry.screenXForTime(center, paddingLeft, viewport,
                scroll, duration, waveWidth), EPS);
        assertEquals(center, WaveformGeometry.timeForScreenX(playhead, paddingLeft, viewport,
                scroll, duration, waveWidth));
    }

    @Test
    public void visibleEdgesAreHalfAViewportAroundThePlayhead() {
        float paddingLeft = 10f;
        float viewport = 1_000f;
        float scroll = 1_234f;
        long duration = 120_000L;
        float waveWidth = 4_000f;

        long start = WaveformGeometry.visibleStartMs(paddingLeft, viewport, scroll, duration,
                waveWidth);
        long end = WaveformGeometry.visibleEndMs(paddingLeft, viewport, scroll, duration,
                waveWidth);
        long center = WaveformGeometry.centerTimeMs(scroll, duration, waveWidth);
        assertTrue(start < center);
        assertTrue(end > center);
        // Half a viewport on each side: 500 px / 4000 px * 120 s = 15 s.
        assertEquals(center - 15_000L, start);
        assertEquals(center + 15_000L, end);
    }

    @Test
    public void screenXClampsOutsideTheTrack() {
        float paddingLeft = 0f;
        float viewport = 1_000f;
        long duration = 100_000L;
        float waveWidth = 4_000f;
        assertEquals(0L, WaveformGeometry.timeForScreenX(-5_000f, paddingLeft, viewport, 0f,
                duration, waveWidth));
        assertEquals(duration, WaveformGeometry.timeForScreenX(9_000f, paddingLeft, viewport,
                0f, duration, waveWidth));
    }

    @Test
    public void scrollIsClampedToTheStrip() {
        assertEquals(0f, WaveformGeometry.clampScroll(-10f, 4_000f), EPS);
        assertEquals(4_000f, WaveformGeometry.clampScroll(9_000f, 4_000f), EPS);
        assertEquals(1_500f, WaveformGeometry.clampScroll(1_500f, 4_000f), EPS);
        assertEquals(0f, WaveformGeometry.clampScroll(100f, 0f), EPS);
    }

    @Test
    public void rulerStepAvoidsOverlappingLabels() {
        // 13 px per second, 116 px minimum label width → 10 s labels (130 px).
        assertEquals(10L, WaveformGeometry.rulerStepSeconds(13f, 116f));
        // Very zoomed out: 60 s labels are the first that fit.
        assertEquals(60L, WaveformGeometry.rulerStepSeconds(2.6f, 116f));
        // Warped zoom: fall back to the largest step instead of returning 0.
        assertEquals(1_800L, WaveformGeometry.rulerStepSeconds(0.01f, 116f));
        assertEquals(1_800L, WaveformGeometry.rulerStepSeconds(0f, 116f));
    }

    @Test
    public void rulerStepIsConvertedToMilliseconds() {
        // Regression: the view used to treat the seconds value as milliseconds, drawing a
        // label every 10 ms (a solid grey bar and thousands of drawText calls per frame).
        assertEquals(10_000L, WaveformGeometry.rulerStepMs(13f, 116f));
        assertEquals(60_000L, WaveformGeometry.rulerStepMs(2.6f, 116f));
        assertEquals(1_800_000L, WaveformGeometry.rulerStepMs(0.01f, 116f));
        assertTrue(WaveformGeometry.rulerStepMs(100_000f, 1f) >= 1_000L);
    }

    @Test
    public void timeClampHandlesUnknownDuration() {
        assertEquals(0L, WaveformGeometry.clampTime(500L, 0L));
        assertEquals(0L, WaveformGeometry.clampTime(-500L, 10_000L));
        assertEquals(10_000L, WaveformGeometry.clampTime(50_000L, 10_000L));
    }

    @Test
    public void interpolationBridgesTheTickerAtEverySpeed() {
        long base = 1_000_000L;
        long duration = 180_000L;
        assertEquals(10_100L, WaveformGeometry.interpolatedPosition(10_000L, base, 1f,
                base + 100L, duration));
        assertEquals(10_200L, WaveformGeometry.interpolatedPosition(10_000L, base, 2f,
                base + 100L, duration));
        assertEquals(10_050L, WaveformGeometry.interpolatedPosition(10_000L, base, 0.5f,
                base + 100L, duration));
    }

    @Test
    public void interpolationNeverGoesBackwardsOrPastTheEnd() {
        long base = 1_000_000L;
        long duration = 180_000L;
        assertEquals(10_000L, WaveformGeometry.interpolatedPosition(10_000L, base, 1f,
                base - 500L, duration));
        assertEquals(10_100L, WaveformGeometry.interpolatedPosition(10_000L, base, Float.NaN,
                base + 100L, duration));
        assertEquals(10_100L, WaveformGeometry.interpolatedPosition(10_000L, base, 0f,
                base + 100L, duration));
        assertEquals(duration, WaveformGeometry.interpolatedPosition(179_900L, base, 1f,
                base + 5_000L, duration));
        // Duration 0 means "unknown": do not clamp.
        assertEquals(184_900L, WaveformGeometry.interpolatedPosition(179_900L, base, 1f,
                base + 5_000L, 0L));
    }
}
