package com.local.musicspeed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Waveform peak accumulation, normalisation and time/index mapping. */
public class WaveformMathTest {

    private static final float EPSILON = 0.0001f;

    @Test
    public void bucketBoundariesUseMaximumAmplitude() {
        short[] mono = {100, 200, 0, 0, 300, 400, 0, 0};
        // Eight samples spread evenly over the minimum of 64 buckets: sample n -> bucket n*8.
        float[] peaks = WaveformMath.peaksFromMono(mono, 64);
        assertEquals(64, peaks.length);
        assertEquals(0.25f, peaks[0], EPSILON);
        assertEquals(0.5f, peaks[8], EPSILON);
        assertEquals(0f, peaks[16], EPSILON);
        assertEquals(0.75f, peaks[32], EPSILON);
        assertEquals(1.0f, peaks[40], EPSILON);

        WaveformMath.Accumulator accumulator = new WaveformMath.Accumulator(2, 4);
        accumulator.addMono(new short[]{100, 200, 300, 400}, 0, 4);
        float[] two = accumulator.finish();
        assertEquals(0.5f, two[0], EPSILON);
        assertEquals(1.0f, two[1], EPSILON);
    }

    @Test
    public void silenceProducesZerosInsteadOfNaN() {
        float[] peaks = WaveformMath.peaksFromMono(new short[100], 64);
        assertEquals(64, peaks.length);
        for (float peak : peaks) {
            assertEquals(0f, peak, EPSILON);
        }
    }

    @Test
    public void clippedSamplesStayInZeroToOneRange() {
        short[] mono = {Short.MIN_VALUE, Short.MAX_VALUE, 0, 1000, -1000};
        for (float peak : WaveformMath.peaksFromMono(mono, 64)) {
            assertTrue(peak >= 0f);
            assertTrue(peak <= 1f);
        }
    }

    @Test
    public void emptyInputIsSafe() {
        float[] peaks = WaveformMath.peaksFromMono(null, 64);
        assertEquals(64, peaks.length);
        for (float peak : peaks) {
            assertEquals(0f, peak, EPSILON);
        }
        assertEquals(64, WaveformMath.peaksFromMono(new short[0], 64).length);
    }

    @Test
    public void bucketCountIsClampedToSafeRange() {
        assertEquals(1, WaveformMath.peaksFromMono(new short[1], 0).length);
        assertEquals(1, WaveformMath.peaksFromMono(new short[1], -5).length);
        assertEquals(WaveformMath.MAX_BUCKETS, WaveformMath.peaksFromMono(new short[1], 999999).length);
    }

    @Test
    public void interleavedStereoIsDownmixedToMono() {
        // Frames: (1000, -1000) -> 0, (8000, 8000) -> 8000, (-4000, 0) -> -2000
        short[] stereo = {1000, -1000, 8000, 8000, -4000, 0};
        float[] peaks = WaveformMath.peaksFromInterleaved(stereo, 2, 64);
        assertEquals(0f, peaks[0], EPSILON);
        assertEquals(1.0f, peaks[21], EPSILON);
        float highest = 0f;
        for (float peak : peaks) {
            highest = Math.max(highest, peak);
        }
        assertEquals(1.0f, highest, EPSILON);
    }

    @Test
    public void timeAndIndexRoundTripAndClamp() {
        assertEquals(0, WaveformMath.indexForTime(0, 60_000, 1000));
        assertEquals(500, WaveformMath.indexForTime(30_000, 60_000, 1000));
        assertEquals(999, WaveformMath.indexForTime(60_000, 60_000, 1000));
        assertEquals(999, WaveformMath.indexForTime(90_000, 60_000, 1000));
        assertEquals(0, WaveformMath.indexForTime(-10, 60_000, 1000));
        assertEquals(30_000, WaveformMath.timeForIndex(500, 60_000, 1000));
        assertEquals(0, WaveformMath.timeForIndex(-5, 60_000, 1000));
        assertEquals(59_940, WaveformMath.timeForIndex(999, 60_000, 1000));
        assertEquals(0, WaveformMath.timeForIndex(5, 0, 1000));
    }

    @Test
    public void samplesBeyondTheEstimateFoldIntoTheLastBucket() {
        short[] mono = {1000, 2000, 3000, 4000, 5000};
        WaveformMath.Accumulator accumulator = new WaveformMath.Accumulator(64, 2);
        accumulator.addMono(mono, 0, mono.length);
        float[] peaks = accumulator.finish();
        assertEquals(0.2f, peaks[0], EPSILON);
        assertEquals(0.4f, peaks[32], EPSILON);
        assertEquals(1.0f, peaks[63], EPSILON);
    }
}
