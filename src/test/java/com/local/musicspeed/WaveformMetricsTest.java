package com.local.musicspeed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Band separation and energy metrics for the coloured waveform: a bass sine must show up in
 * {@code low}, a treble sine in {@code high}, silence must stay zero without NaN, and every
 * output array has to be normalised into 0..1.
 */
public class WaveformMetricsTest {

    private static final int RATE = 44_100;
    private static final float EPS = 1e-4f;

    /** Mono sine at {@code frequency} Hz, 1 s long, amplitude 0.5 full scale. */
    private static short[] sine(float frequency, float amplitude, int sampleRate, int samples) {
        short[] data = new short[samples];
        for (int i = 0; i < samples; i++) {
            data[i] = (short) Math.round(amplitude * 32767.0 * Math.sin(2.0 * Math.PI * frequency * i / sampleRate));
        }
        return data;
    }

    private static void assertUnitRange(float[] values) {
        for (float value : values) {
            assertTrue("NaN in output", !Float.isNaN(value));
            assertTrue("value below 0: " + value, value >= 0f);
            assertTrue("value above 1: " + value, value <= 1f);
        }
    }

    @Test
    public void bucketCountFollowsDurationWithClamps() {
        assertEquals(WaveformMetrics.MIN_BUCKETS, WaveformMetrics.bucketCountFor(1_000L));
        assertEquals(18_000, WaveformMetrics.bucketCountFor(180_000L));
        assertEquals(30_000, WaveformMetrics.bucketCountFor(300_000L));
        assertEquals(WaveformMetrics.MAX_BUCKETS, WaveformMetrics.bucketCountFor(3_600_000L));
        // unknown duration assumes a 10-minute track so the resolution never collapses
        long assumed = WaveformMetrics.bucketCountFor(600_000L);
        assertEquals(60_000, assumed);
        assertEquals(assumed, WaveformMetrics.bucketCountFor(0L));
        assertEquals(assumed, WaveformMetrics.bucketCountFor(-50L));
        // 20 s visible window = 2000 buckets (10 ms resolution)
        assertEquals(2_000, WaveformMetrics.bucketCountFor(20_000L));
    }

    @Test
    public void lowSineIsBassDominant() {
        short[] mono = sine(60f, 0.5f, RATE, RATE);
        WaveformMetrics.Accumulator accumulator = new WaveformMetrics.Accumulator(4, RATE, RATE);
        accumulator.addMono(mono);
        WaveformMetrics.Waveform waveform = accumulator.finish();
        float low = waveform.low[2];
        float high = waveform.high[2];
        assertTrue("low=" + low + " high=" + high, low > high + 0.3f);
        assertTrue(low > 0.9f);
        assertUnitRange(waveform.low);
        assertUnitRange(waveform.high);
        assertUnitRange(waveform.rms);
        assertUnitRange(waveform.peaks);
    }

    @Test
    public void highSineIsTrebleDominant() {
        short[] mono = sine(5_000f, 0.5f, RATE, RATE);
        WaveformMetrics.Accumulator accumulator = new WaveformMetrics.Accumulator(4, RATE, RATE);
        accumulator.addMono(mono);
        WaveformMetrics.Waveform waveform = accumulator.finish();
        float low = waveform.low[2];
        float high = waveform.high[2];
        assertTrue("low=" + low + " high=" + high, high > low * 4f);
        assertTrue(high > 0.9f);
    }

    @Test
    public void silenceGivesZeroWithoutNaN() {
        short[] silence = new short[RATE];
        WaveformMetrics.Waveform waveform = WaveformMetrics.fromMono(silence, 64, RATE);
        for (int i = 0; i < waveform.length(); i++) {
            assertEquals(0f, waveform.peaks[i], EPS);
            assertEquals(0f, waveform.rms[i], EPS);
            assertEquals(0f, waveform.low[i], EPS);
            assertEquals(0f, waveform.high[i], EPS);
        }
    }

    @Test
    public void emptyInputIsSafe() {
        WaveformMetrics.Waveform waveform = WaveformMetrics.fromMono(null, 32, RATE);
        assertEquals(32, waveform.length());
        assertUnitRange(waveform.peaks);
        assertEquals(0f, waveform.rms[0], EPS);
        assertEquals(0f, waveform.low[0], EPS);
        assertEquals(0f, waveform.high[0], EPS);
    }

    @Test
    public void dcOffsetIsNotReportedAsBass() {
        short[] dc = new short[RATE];
        for (int i = 0; i < dc.length; i++) {
            dc[i] = 8_000;
        }
        WaveformMetrics.Waveform waveform = WaveformMetrics.fromMono(dc, 8, RATE);
        // The bucket's running mean removes the offset, so the low band is tiny compared to
        // a real bass tone of the same amplitude.
        assertTrue("low=" + waveform.low[4], waveform.low[4] < 0.35f);
        assertEquals(1.0f, waveform.peaks[4], EPS);
    }

    @Test
    public void quietAndLoudSectionsAreDistinguishableByEnergy() {
        // 0.5 s loud, 0.5 s quiet at the same frequency
        short[] loud = sine(440f, 0.8f, RATE, RATE / 2);
        short[] quiet = sine(440f, 0.05f, RATE, RATE / 2);
        WaveformMetrics.Accumulator accumulator = new WaveformMetrics.Accumulator(2, RATE, RATE);
        accumulator.addMono(loud);
        accumulator.addMono(quiet);
        WaveformMetrics.Waveform waveform = accumulator.finish();
        assertTrue(waveform.rms[0] > 0.9f);
        assertTrue("quiet rms=" + waveform.rms[1], waveform.rms[1] < 0.2f);
        // Peak normalisation keeps the relative amplitude as well.
        assertEquals(1.0f, waveform.peaks[0], EPS);
        assertTrue(waveform.peaks[1] < 0.15f);
    }

    @Test
    public void stereoIsDownmixedAndStaysInRange() {
        short[] stereo = new short[]{1000, -1000, 8000, 8000, -4000, 0, 0, 0};
        WaveformMetrics.Accumulator accumulator = new WaveformMetrics.Accumulator(2, 4, RATE);
        accumulator.addInterleaved(stereo, 0, stereo.length, 2);
        WaveformMetrics.Waveform waveform = accumulator.finish();
        assertEquals(2, waveform.length());
        assertUnitRange(waveform.peaks);
        // first frame averages to 0, then 8000 dominates its bucket
        assertTrue(waveform.peaks[0] > 0f);
    }

    @Test
    public void bucketBoundariesAssignEachSampleOnce() {
        short[] mono = new short[]{1000, 2000, 3000, 4000, 5000, 6000, 7000, 8000};
        WaveformMetrics.Waveform waveform = WaveformMetrics.fromMono(mono, 4, RATE);
        // samples 0-1 -> bucket 0, 2-3 -> bucket 1, 4-5 -> bucket 2, 6-7 -> bucket 3
        assertEquals(0.25f, waveform.peaks[0], EPS);
        assertEquals(0.5f, waveform.peaks[1], EPS);
        assertEquals(0.75f, waveform.peaks[2], EPS);
        assertEquals(1.0f, waveform.peaks[3], EPS);
        // more samples than the estimate all fold into the last bucket instead of crashing
        WaveformMetrics.Accumulator overflow = new WaveformMetrics.Accumulator(4, 2, RATE);
        overflow.addMono(mono);
        float[] peaks = overflow.finishPeaks();
        assertEquals(4, peaks.length);
        assertEquals(1.0f, peaks[3], EPS);
        assertEquals(0f, WaveformMetrics.safeUnit(Float.NaN), EPS);
    }

    @Test
    public void lowAndHighAreComplementaryForBroadbandNoise() {
        java.util.Random random = new java.util.Random(7);
        short[] noise = new short[RATE];
        for (int i = 0; i < noise.length; i++) {
            noise[i] = (short) random.nextInt(20_000);
        }
        WaveformMetrics.Waveform waveform = WaveformMetrics.fromMono(noise, 8, RATE);
        for (int i = 0; i < waveform.length(); i++) {
            float total = waveform.low[i] + waveform.high[i];
            assertTrue("bucket " + i + " has no band energy", total > 0f);
            assertTrue("high must dominate noise: " + waveform.low[i] + " vs " + waveform.high[i],
                    waveform.high[i] > waveform.low[i]);
        }
        assertUnitRange(waveform.low);
        assertUnitRange(waveform.high);
    }

    @Test
    public void zeroBucketCountFallsBackToMinimum() {
        WaveformMetrics.Accumulator accumulator = new WaveformMetrics.Accumulator(0, 100, RATE);
        assertEquals(WaveformMetrics.MIN_BUCKETS, accumulator.bucketCount());
        assertEquals(WaveformMetrics.MIN_BUCKETS, accumulator.finishPeaks().length);
    }
}
