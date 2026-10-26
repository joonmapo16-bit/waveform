package com.local.musicspeed;

/**
 * Streaming per-bucket metrics for the coloured waveform: amplitude, energy and a cheap
 * low/high split. Pure Java (no Android classes) so it can be unit tested on the JVM and
 * reused by {@link WaveformDecoder} on its background thread.
 *
 * <p>Each mono sample (after channel downmix) is pushed through a one-pole low-pass
 * ({@code low += alpha * (s - low)}); the complement {@code s - low} is the high band. No FFT
 * is needed. The bucket keeps</p>
 *
 * <ul>
 *   <li>{@code peak} – max |sample| (drives the bar height),</li>
 *   <li>{@code rms}  – sqrt(mean(sample²)) (drives brightness),</li>
 *   <li>{@code low}  – sqrt(mean(low²)),</li>
 *   <li>{@code high} – sqrt(mean(high²)).</li>
 * </ul>
 *
 * <p>Memory is four float arrays for the whole track, e.g. 18,000 buckets for a 3-minute
 * song at 100 buckets/second ≈ 288 KB. The PCM stream itself is never retained.</p>
 */
public final class WaveformMetrics {

    /** 10 ms resolution: a 20 s visible window is 2,000 buckets across the screen width. */
    public static final int BUCKETS_PER_SECOND = 100;
    /** 20 minutes at 100 buckets/second. */
    public static final int MAX_BUCKETS = 120_000;
    /** Short/unknown tracks still get a usable resolution. */
    public static final int MIN_BUCKETS = 2_000;
    /** One-pole low-pass corner; energy below this leans toward the "bass" colour. */
    public static final float LOW_PASS_HZ = 200f;
    /** Corner of the slow DC tracker subtracted from the bass band (well below any note). */
    public static final float DC_TRACKER_HZ = 10f;
    /** Scale of the 16-bit sample domain (2^15 - 1). */
    public static final float SAMPLE_SCALE = 32767f;
    /** A raw 16-bit bucket rounding to -32768 (clipped sample) must still fit 0..1. */
    private static final float SCALE_EPSILON = 1e-6f;

    private WaveformMetrics() {
    }

    /**
     * Turns 0..1 cache values back into 16-bit domain magnitudes so a level-thresholded mix
     * can be decided exactly once, on values the cache has rounded to its 0..1 output scale.
     *
     * <p>The cache quantises float32 values of a 0..1 array, so multiplying by
     * {@link #SAMPLE_SCALE} used to round a clipped sample (32768 raw) to 32767.999 and make
     * any "below a ramp edge" test miss; the epsilon keeps such values on the darker side.</p>
     */
    public static float toLevel(float unit) {
        return safeUnit(unit) * SAMPLE_SCALE - SCALE_EPSILON;
    }

    /**
     * Bucket count for a track: duration × 100 buckets/s, clamped to MIN..MAX.
     *
     * @param durationMs known duration, or 0/negative when unknown (a ten-minute track is
     *                   assumed so a wrong duration cannot starve the resolution)
     */
    public static int bucketCountFor(long durationMs) {
        long duration = durationMs > 0L ? durationMs : 600_000L;
        long buckets = duration * BUCKETS_PER_SECOND / 1000L;
        if (buckets < MIN_BUCKETS) {
            return MIN_BUCKETS;
        }
        if (buckets > MAX_BUCKETS) {
            return MAX_BUCKETS;
        }
        return (int) buckets;
    }

    /** Immutable result set handed to the cache and the view. */
    public static final class Waveform {
        public final float[] peaks;
        public final float[] rms;
        public final float[] low;
        public final float[] high;

        public Waveform(float[] peaks, float[] rms, float[] low, float[] high) {
            this.peaks = peaks;
            this.rms = rms;
            this.low = low;
            this.high = high;
        }

        public int length() {
            return peaks == null ? 0 : peaks.length;
        }
    }

    /**
     * Streaming bucket accumulator.
     *
     * <p>{@link #finish()} normalises each array to 0..1 by its own maximum so a quiet track
     * still shows contrast; silence becomes 0 (never NaN).</p>
     */
    public static final class Accumulator {

        private final int bucketCount;
        private final long totalSamples;
        private final float[] peaks;
        private final double[] sumSquares;
        private final double[] lowSquares;
        private final double[] highSquares;
        private final float alpha;
        private final float dcAlpha;
        private final int sampleRate;

        private long sampleCursor;
        /** One-pole low-pass state (≈ 200 Hz). */
        private float lowState;
        /** Much slower one-pole state used only to subtract DC from the bass band. */
        private float dcState;

        public Accumulator(int bucketCount, long totalSamples, int sampleRate) {
            int clamped = bucketCount <= 0 ? MIN_BUCKETS
                    : Math.min(MAX_BUCKETS, Math.max(1, bucketCount));
            this.bucketCount = clamped;
            this.totalSamples = Math.max(1L, totalSamples);
            this.peaks = new float[clamped];
            this.sumSquares = new double[clamped];
            this.lowSquares = new double[clamped];
            this.highSquares = new double[clamped];
            this.sampleRate = Math.max(1, sampleRate);
            this.alpha = (float) (1.0 - Math.exp(-2.0 * Math.PI * LOW_PASS_HZ / this.sampleRate));
            this.dcAlpha = (float) (1.0 - Math.exp(-2.0 * Math.PI * DC_TRACKER_HZ / this.sampleRate));
        }

        public int bucketCount() {
            return bucketCount;
        }

        public int sampleRate() {
            return sampleRate;
        }

        /**
         * Adds interleaved 16-bit samples. Channels are averaged to mono: enough for a visual
         * envelope and independent of the channel count.
         */
        public void addInterleaved(short[] data, int offset, int count, int channelCount) {
            if (data == null || count <= 0 || channelCount <= 0) {
                return;
            }
            int end = Math.min(data.length, offset + count);
            for (int i = offset; i + channelCount <= end; i += channelCount) {
                int sum = 0;
                for (int channel = 0; channel < channelCount; channel++) {
                    sum += data[i + channel];
                }
                int bucket = bucketForCursor();
                float sample = clampUnit(sum / (float) channelCount / SAMPLE_SCALE);
                float amplitude = sample < 0f ? -sample : sample;
                if (amplitude > peaks[bucket]) {
                    peaks[bucket] = amplitude;
                }
                sumSquares[bucket] += (double) sample * sample;

                // The one-pole low-pass also passes DC, so track a much slower running mean and
                // remove it: a constant offset is silence for colour purposes and must not look
                // like bass energy.
                lowState = lowState + alpha * (sample - lowState);
                dcState = dcState + dcAlpha * (sample - dcState);
                float bass = lowState - dcState;
                float low = Math.abs(bass);
                float high = Math.abs(sample - lowState);
                lowSquares[bucket] += (double) low * low;
                highSquares[bucket] += (double) high * high;

                sampleCursor++;
            }
        }

        /** Convenience for tests: adds already mono samples (whole array). */
        public void addMono(short[] data) {
            addMono(data, 0, data == null ? 0 : data.length);
        }

        public void addMono(short[] data, int offset, int count) {
            addInterleaved(data, offset, count, 1);
        }

        private int bucketForCursor() {
            if (sampleCursor >= totalSamples) {
                return bucketCount - 1;
            }
            long bucket = sampleCursor * (long) bucketCount / totalSamples;
            if (bucket < 0) {
                return 0;
            }
            return (int) Math.min(bucketCount - 1, bucket);
        }

        /**
         * Normalised 0..1 arrays. Safe (all zeros) for silence or empty input.
         *
         * <p>Energy arrays share one scale (the loudest bucket of either band) so bass and
         * treble stay comparable with each other; scaling each band on its own would make a
         * pure bass track report "treble 1.0" as well and wash every colour toward mid-hue.</p>
         */
        public Waveform finish() {
            return new Waveform(finishPeaks(), finishRms(), finishLow(), finishHigh());
        }

        /** Peak-only normalisation (legacy accumulator behaviour, each array on its own maximum). */
        public float[] finishPeaks() {
            return normalise(peaks);
        }

        public float[] finishRms() {
            return normaliseRms(sumSquares, highestOf(sumSquares));
        }

        public float[] finishLow() {
            return normaliseRms(lowSquares, bandScale());
        }

        public float[] finishHigh() {
            return normaliseRms(highSquares, bandScale());
        }

        private double bandScale() {
            return Math.max(highestOf(lowSquares), highestOf(highSquares));
        }

        private static double highestOf(double[] values) {
            double highest = 0.0;
            for (double value : values) {
                if (value > highest) {
                    highest = value;
                }
            }
            return highest;
        }

        private float[] normalise(float[] values) {
            float highest = 0f;
            for (float value : values) {
                if (value > highest) {
                    highest = value;
                }
            }
            float[] result = new float[bucketCount];
            if (!(highest > 0f) || Float.isInfinite(highest)) {
                return result;
            }
            float scale = 1f / Math.max(highest, 1e-6f);
            for (int i = 0; i < bucketCount; i++) {
                result[i] = safeUnit(values[i] * scale);
            }
            return result;
        }

        private float[] normaliseRms(double[] squares, double highest) {
            float[] result = new float[bucketCount];
            if (!(highest > 0.0) || Double.isInfinite(highest) || Double.isNaN(highest)) {
                return result;
            }
            // sqrt(mean) = sqrt(sum / samples); only the ratio between buckets matters, so
            // scaling by the largest sum is equivalent and avoids dividing by the count.
            double scale = 1.0 / Math.sqrt(highest);
            for (int i = 0; i < bucketCount; i++) {
                double value = squares[i] <= 0.0 ? 0.0 : Math.sqrt(squares[i]) * scale;
                result[i] = safeUnit((float) value);
            }
            return result;
        }
    }

    private static float clampUnit(float value) {
        if (value >= 1f) {
            return 1f;
        }
        if (value <= -1f) {
            return -1f;
        }
        return value;
    }

    /** Clamps to 0..1 and maps NaN to 0 so nothing weird reaches the canvas or the cache. */
    public static float safeUnit(float value) {
        if (Float.isNaN(value) || value <= 0f) {
            return 0f;
        }
        return value >= 1f ? 1f : value;
    }

    /** Clamp to 0..1; NaN is treated as 0 (same rule as {@link #safeUnit(float)}). */
    public static double safeUnit(double value) {
        if (Double.isNaN(value) || value <= 0.0) {
            return 0.0;
        }
        return value >= 1.0 ? 1.0 : value;
    }

    /** Builds only the peak array from an in-memory mono buffer (legacy helper). */
    public static float[] peaksFromMono(short[] samples, int bucketCount) {
        return build(samples, bucketCount, 44_100).finishPeaks();
    }

    /** Builds all four arrays from an in-memory mono buffer at a given sample rate. */
    public static Waveform fromMono(short[] samples, int bucketCount, int sampleRate) {
        return build(samples, bucketCount, sampleRate).finish();
    }

    private static Accumulator build(short[] samples, int bucketCount, int sampleRate) {
        int count = samples == null ? 0 : samples.length;
        Accumulator accumulator = new Accumulator(bucketCount, Math.max(1, count), sampleRate);
        if (count > 0) {
            accumulator.addMono(samples, 0, count);
        }
        return accumulator;
    }
}
