package com.local.musicspeed;

/**
 * Pure math for turning decoded 16-bit PCM into a fixed number of waveform peaks.
 *
 * <p>The decoder streams audio chunks into an {@link Accumulator} so the whole PCM buffer is
 * never kept in memory. Each bucket keeps the maximum absolute amplitude it saw; the result
 * is normalised to 0..1 for drawing. Keeping this Android-free is what makes the bucket
 * boundary / silence / clipping behaviour unit-testable.</p>
 */
public final class WaveformMath {

    public static final int MIN_BUCKETS = 64;
    public static final int MAX_BUCKETS = 8192;

    private WaveformMath() {
    }

    /**
     * Streaming peak builder.
     *
     * @param bucketCount number of output values
     * @param totalSamples estimated number of mono samples in the track (must be > 0)
     */
    public static final class Accumulator {

        private final int bucketCount;
        private final long totalSamples;
        private final float[] peaks;
        private long sampleCursor;

        public Accumulator(int bucketCount, long totalSamples) {
            this.bucketCount = Math.max(1, Math.min(MAX_BUCKETS, bucketCount));
            this.totalSamples = Math.max(1L, totalSamples);
            this.peaks = new float[this.bucketCount];
        }

        public int bucketCount() {
            return bucketCount;
        }

        /**
         * Adds interleaved 16-bit samples. Channels are averaged to mono, which is enough
         * for a visual envelope and keeps the memory footprint independent of channel count.
         */
        public void addInterleaved(short[] data, int offset, int count, int channelCount) {
            if (data == null || count <= 0 || channelCount <= 0) {
                return;
            }
            int end = Math.min(data.length, offset + count);
            int frameSize = channelCount;
            for (int i = offset; i + frameSize <= end; i += frameSize) {
                int sum = 0;
                for (int channel = 0; channel < frameSize; channel++) {
                    sum += data[i + channel];
                }
                int mono = sum / frameSize;
                int amplitude = mono < 0 ? -mono : mono;
                int bucket = bucketForCursor();
                if (amplitude > peaks[bucket]) {
                    peaks[bucket] = amplitude;
                }
                sampleCursor++;
            }
        }

        /** Convenience for tests: adds already mono samples. */
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

        /** Normalises the accumulated amplitudes to 0..1. */
        public float[] finish() {
            float highest = 0f;
            for (float peak : peaks) {
                if (peak > highest) {
                    highest = peak;
                }
            }
            float[] result = new float[bucketCount];
            if (highest <= 0.0001f) {
                return result;
            }
            for (int i = 0; i < bucketCount; i++) {
                float value = peaks[i] / highest;
                result[i] = Math.max(0f, Math.min(1f, value));
            }
            return result;
        }
    }

    /** Builds peaks from an in-memory mono buffer. Useful for tests and small previews. */
    public static float[] peaksFromMono(short[] samples, int bucketCount) {
        int count = samples == null ? 0 : samples.length;
        Accumulator accumulator = new Accumulator(bucketCount, Math.max(1, count));
        if (count > 0) {
            accumulator.addMono(samples, 0, count);
        }
        return accumulator.finish();
    }

    /** Builds peaks from an in-memory interleaved buffer. */
    public static float[] peaksFromInterleaved(short[] samples, int channelCount, int bucketCount) {
        int count = samples == null ? 0 : samples.length;
        Accumulator accumulator = new Accumulator(bucketCount, Math.max(1, count / Math.max(1, channelCount)));
        if (count > 0) {
            accumulator.addInterleaved(samples, 0, count, Math.max(1, channelCount));
        }
        return accumulator.finish();
    }

    /** Maps a playback position to a peak index. */
    public static int indexForTime(long timeMs, long durationMs, int bucketCount) {
        if (bucketCount <= 0) {
            return 0;
        }
        if (durationMs <= 0) {
            return timeMs > 0 ? bucketCount - 1 : 0;
        }
        long clamped = Math.max(0L, Math.min(durationMs, timeMs));
        int index = (int) (clamped * (long) bucketCount / durationMs);
        return Math.max(0, Math.min(bucketCount - 1, index));
    }

    /** Maps a peak index back to a playback position. */
    public static long timeForIndex(int index, long durationMs, int bucketCount) {
        if (durationMs <= 0 || bucketCount <= 0) {
            return 0L;
        }
        int clamped = Math.max(0, Math.min(bucketCount - 1, index));
        return durationMs * (long) clamped / bucketCount;
    }
}
