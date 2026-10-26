package com.local.musicspeed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * JVM tests for the per-bar aggregation. The key regression is the 1.4 bug: squeezing a
 * whole track into a few columns with {@code max} made every bar full height, so the waveform
 * looked like a solid block that never moved. 1.6 adds p95 + gamma 0.75 height normalisation,
 * so the expected heights changed while the average/boundary rules stayed the same.
 */
public class WaveformBarsTest {

    private static final float EPS = 0.001f;

    private static WaveformMetrics.Waveform waveform(float[] peaks, float[] rms,
                                                     float[] low, float[] high) {
        return new WaveformMetrics.Waveform(peaks, rms, low, high);
    }

    private static float[] fill(int length, float value) {
        float[] values = new float[length];
        for (int i = 0; i < length; i++) {
            values[i] = value;
        }
        return values;
    }

    @Test
    public void averageIsUsedInsteadOfMaximum() {
        float[] peaks = {1f, 0f, 1f, 0f};
        WaveformBars bars = WaveformBars.aggregate(
                waveform(peaks, peaks, fill(4, 0f), fill(4, 0f)), 2);
        assertEquals(2, bars.count);
        // max() would give 1.0 for both bars; the average keeps the alternating dynamics.
        // p95 is 1.0 here (two of four buckets), so gain ~1 and gamma 0.75 lifts 0.5.
        assertEquals(0.5946f, bars.amplitude[0], 0.01f);
        assertEquals(0.5946f, bars.amplitude[1], 0.01f);
    }

    @Test
    public void constantLevelIsNormalisedToFullHeight() {
        // p95 normalisation maps a steady level to 1.0 by design; the shape stays flat.
        float[] peaks = fill(1_000, 0.25f);
        WaveformBars bars = WaveformBars.aggregate(
                waveform(peaks, peaks, fill(1_000, 0f), fill(1_000, 0f)), 40);
        for (int i = 0; i < bars.count; i++) {
            assertTrue("constant level should fill: " + bars.amplitude[i],
                    bars.amplitude[i] > 0.99f);
        }
    }

    @Test
    public void barsCoverEveryBucketExactlyOnce() {
        float[] peaks = new float[10];
        for (int i = 0; i < peaks.length; i++) {
            peaks[i] = (i + 1) / 10f;
        }
        WaveformBars bars = WaveformBars.aggregate(
                waveform(peaks, peaks, fill(10, 0f), fill(10, 0f)), 3);
        assertEquals(3, bars.count);
        // p95 = 0.9 (n=10) and gain = 1/0.9; gamma 0.75 lifts the low means.
        assertEquals(0.324f, bars.amplitude[0], 0.01f);   // buckets 0..2 (0.1+0.2+0.3)
        assertEquals(0.644f, bars.amplitude[1], 0.01f);   // buckets 3..5 (0.4+0.5+0.6)
        assertEquals(0.957f, bars.amplitude[2], 0.01f);   // buckets 6..9 (0.7+0.8+0.9+1.0)
    }

    @Test
    public void energyAndAmplitudeAreIndependent() {
        float[] peaks = fill(8, 0.9f);
        float[] rms = {0f, 0f, 0f, 0f, 1f, 1f, 1f, 1f};
        WaveformBars bars = WaveformBars.aggregate(
                waveform(peaks, rms, fill(8, 0f), fill(8, 0f)), 2);
        assertTrue(bars.amplitude[0] > 0.99f);
        assertTrue(bars.amplitude[1] > 0.99f);
        assertEquals(0f, bars.energy[0], EPS);
        assertEquals(1f, bars.energy[1], EPS);
    }

    @Test
    public void silenceIsAllZero() {
        WaveformBars bars = WaveformBars.aggregate(
                waveform(fill(64, 0f), fill(64, 0f), fill(64, 0f), fill(64, 0f)), 8);
        for (int i = 0; i < bars.count; i++) {
            assertEquals(0f, bars.amplitude[i], EPS);
            assertEquals(0f, bars.energy[i], EPS);
            assertEquals(0f, bars.balance[i], EPS);
        }
    }

    @Test
    public void nanBecomesZeroAndInfinitySaturates() {
        float[] peaks = {Float.NaN, Float.POSITIVE_INFINITY, -1f, 0.5f};
        WaveformBars bars = WaveformBars.aggregate(
                waveform(peaks, peaks, peaks, peaks), 4);
        // NaN and negative values clamp to 0; +Infinity saturates at 1. p95 is 0.5 (n=4),
        // so 0.5 is normalised to full height and no NaN leaks into the output.
        assertEquals(0f, bars.amplitude[0], EPS);
        assertEquals(1f, bars.amplitude[1], EPS);
        assertEquals(0f, bars.amplitude[2], EPS);
        assertTrue(bars.amplitude[3] > 0.99f);
        for (float value : bars.amplitude) {
            assertFalse(Float.isNaN(value));
        }
    }

    @Test
    public void bandBalanceFollowsLowAndHighEnergy() {
        WaveformBars lowOnly = WaveformBars.aggregate(
                waveform(fill(8, 0.5f), fill(8, 0.5f), fill(8, 1f), fill(8, 0f)), 2);
        WaveformBars highOnly = WaveformBars.aggregate(
                waveform(fill(8, 0.5f), fill(8, 0.5f), fill(8, 0f), fill(8, 1f)), 2);
        WaveformBars balanced = WaveformBars.aggregate(
                waveform(fill(8, 0.5f), fill(8, 0.5f), fill(8, 0.5f), fill(8, 0.5f)), 2);

        assertEquals(-1f, lowOnly.balance[0], EPS);
        assertEquals(1f, highOnly.balance[0], EPS);
        assertEquals(0f, balanced.balance[0], EPS);
    }

    @Test
    public void weakBandsAreTreatedAsNeutral() {
        // Both bands below BAND_PRESENCE: do not invent a hue.
        float[] low = fill(8, WaveformBars.BAND_PRESENCE / 2f);
        float[] high = fill(8, WaveformBars.BAND_PRESENCE / 2f);
        WaveformBars bars = WaveformBars.aggregate(
                waveform(fill(8, 0.5f), fill(8, 0.5f), low, high), 2);
        assertEquals(0f, bars.balance[0], EPS);
    }

    @Test
    public void balanceIsSmoothedAcrossNeighbouringBars() {
        // Middle bar is high-only, neighbours are silent: the box filter softens the edge.
        float[] low = fill(9, 0f);
        float[] high = {0f, 0f, 0f, 1f, 1f, 1f, 0f, 0f, 0f};
        WaveformBars bars = WaveformBars.aggregate(
                waveform(fill(9, 0.5f), fill(9, 0.5f), low, high), 3);
        assertEquals(0.5f, bars.balance[0], EPS);          // own 0 + next 1
        assertEquals(1f / 3f, bars.balance[1], EPS);       // smoothed with two silent neighbours
        assertEquals(0.5f, bars.balance[2], EPS);          // previous 1 + own 0
    }

    @Test
    public void moreBarsThanBucketsIsCapped() {
        WaveformBars bars = WaveformBars.aggregate(
                waveform(fill(3, 0.5f), fill(3, 0.5f), fill(3, 0f), fill(3, 0f)), 100);
        assertEquals(3, bars.count);
    }

    @Test
    public void emptyInputIsSafe() {
        assertNotNull(WaveformBars.aggregate(null, 10));
        assertEquals(0, WaveformBars.aggregate(null, 10).count);
        assertEquals(0, WaveformBars.aggregate(
                waveform(new float[0], new float[0], new float[0], new float[0]), 10).count);
        assertEquals(0, WaveformBars.aggregate(
                waveform(fill(4, 0.5f), fill(4, 0.5f), fill(4, 0f), fill(4, 0f)), 0).count);
    }

    @Test
    public void coloursArePrecomputedForPlayedAndUpcoming() {
        WaveformBars bars = WaveformBars.aggregate(
                waveform(fill(8, 0.8f), fill(8, 0.8f), fill(8, 0f), fill(8, 1f)), 2);
        assertEquals(2, bars.playedColors.length);
        assertEquals(2, bars.unplayedColors.length);
        for (int i = 0; i < bars.count; i++) {
            assertEquals(255, (bars.playedColors[i] >>> 24) & 0xFF);
            assertEquals(255, (bars.unplayedColors[i] >>> 24) & 0xFF);
            assertTrue(bars.playedColors[i] != bars.unplayedColors[i]);
        }
        assertEquals(0xFF93A1AF, bars.playedColorAt(-1));
        assertEquals(0xFF93A1AF, bars.unplayedColorAt(99));
    }

    @Test
    public void accessorsAreBoundsSafe() {
        WaveformBars bars = WaveformBars.aggregate(
                waveform(fill(4, 0.5f), fill(4, 0.5f), fill(4, 0f), fill(4, 0f)), 2);
        assertEquals(0f, bars.amplitudeAt(-1), EPS);
        assertEquals(0f, bars.amplitudeAt(99), EPS);
        assertTrue(bars.amplitudeAt(0) > 0.99f);
        assertEquals(0f, bars.energyAt(99), EPS);
        assertEquals(0f, bars.balanceAt(-5), EPS);
    }

    @Test
    public void quietDynamicTrackFillsThePanel() {
        // Body at 5% of full scale, a short transient every 20 buckets (5% of buckets).
        // 1.5 drew this as a ~5% tall line; p95 + gain + gamma must make the body readable
        // and let the bars that contain transients reach near full height.
        int length = 1_000;
        float[] peaks = fill(length, 0.05f);
        for (int i = 0; i < length; i += 20) {
            peaks[i] = 1f;
        }
        WaveformBars bars = WaveformBars.aggregate(
                waveform(peaks, peaks, fill(length, 0f), fill(length, 0f)), 80);
        float[] sorted = bars.amplitude.clone();
        java.util.Arrays.sort(sorted);
        float p95 = sorted[(int) Math.round((sorted.length - 1) * 0.95)];
        assertTrue("p95 bar height " + p95, p95 >= 0.9f);
        // Body-only bars are lifted well above the old 0.05 (gain is capped at 8x).
        assertTrue("body bar " + sorted[0], sorted[0] > 0.45f);
    }

    @Test
    public void normalisationGainIsCappedForNearSilence() {
        // p95 = 0.01 would need 100x; the +18 dB cap keeps the floor quiet (no full panel).
        float[] peaks = fill(1_000, 0.01f);
        WaveformBars bars = WaveformBars.aggregate(
                waveform(peaks, peaks, fill(1_000, 0f), fill(1_000, 0f)), 20);
        for (int i = 0; i < bars.count; i++) {
            assertTrue("capped gain should stay below 0.5: " + bars.amplitude[i],
                    bars.amplitude[i] > 0f && bars.amplitude[i] < 0.5f);
        }
    }

    @Test
    public void heightScaleDoesNotDependOnBarCount() {
        int length = 1_000;
        float[] peaks = new float[length];
        for (int i = 0; i < length; i++) {
            peaks[i] = i < length / 2 ? 0.2f : 0.8f;
        }
        float[] rms = peaks.clone();
        WaveformBars coarse = WaveformBars.aggregate(
                waveform(peaks, rms, fill(length, 0f), fill(length, 0f)), 50);
        WaveformBars fine = WaveformBars.aggregate(
                waveform(peaks, rms, fill(length, 0f), fill(length, 0f)), 500);
        // Same bucket region must have the same height regardless of zoom.
        assertEquals(coarse.amplitude[10], fine.amplitude[100], 0.01f);
        assertEquals(coarse.amplitude[40], fine.amplitude[400], 0.01f);
        // And the quiet half stays lower than the loud half.
        assertTrue(coarse.amplitude[10] < coarse.amplitude[40]);
    }

    @Test
    public void gammaIsMonotonicAndFullScaleStaysFull() {
        float[] peaks = {0.05f, 0.1f, 0.2f, 0.4f, 0.8f, 1f};
        WaveformBars bars = WaveformBars.aggregate(
                waveform(peaks, peaks, fill(peaks.length, 0f), fill(peaks.length, 0f)), 6);
        for (int i = 1; i < bars.count; i++) {
            assertTrue("input ↑ must not lower output: " + bars.amplitude[i - 1] + " → "
                    + bars.amplitude[i], bars.amplitude[i] >= bars.amplitude[i - 1]);
        }
        assertEquals(1f, bars.amplitude[bars.count - 1], EPS);
    }
}
