package com.local.musicspeed;

/**
 * Aggregates the 100 Hz metrics buckets into one value per waveform bar.
 *
 * <p>This mirrors the reference player's {@code barAggregate = 1} mode: a bar shows the
 * <b>average</b> of the buckets it covers, not their maximum. Squeezing a whole track into a
 * handful of bars while taking {@code max} makes almost every bar reach full height; the
 * average keeps the dynamics readable.</p>
 *
 * <p>1.6 normalises the bar heights against the <b>bucket peak p95</b> (not the single loudest
 * bucket) and applies a 0.75 gamma. Quiet/dynamic tracks therefore fill the panel instead of
 * collapsing into a thin line, while the loudest 5% of buckets still saturate. The gain is
 * computed once per bucket set, so changing the zoom (bar count) never changes the scale.
 * Brightness (RMS energy) deliberately keeps the old absolute normalisation.</p>
 *
 * <p>Pure Java on purpose: the aggregation rules and boundary maths are covered by JVM
 * tests and the Android view only paints the resulting arrays.</p>
 */
public final class WaveformBars {

    /** Below this the low/high bands are considered unusable (no hue information). */
    public static final float BAND_PRESENCE = 0.05f;

    /** Histogram resolution for the p95 reference peak. */
    private static final int PEAK_HISTOGRAM_BINS = 1024;
    /** 1 / p95Peak is capped at this gain (~+18 dB) so near-silent noise stays quiet. */
    private static final float MAX_NORMALISATION_GAIN = 8.0f;
    /** Bar height gamma: lifts quiet bodies without flattening transients. */
    private static final float AMPLITUDE_GAMMA = 0.75f;

    /** Amplitude 0..1 per bar; drives the bar height. */
    public final float[] amplitude;
    /** RMS energy 0..1 per bar; drives brightness. */
    public final float[] energy;
    /** Low/high balance -1..1 per bar; drives hue. */
    public final float[] balance;
    /** Pre-computed vivid ARGB for the already-played part. */
    public final int[] playedColors;
    /** Pre-computed muted ARGB for the upcoming part. */
    public final int[] unplayedColors;
    public final int count;

    private WaveformBars(float[] amplitude, float[] energy, float[] balance, int count) {
        this.amplitude = amplitude;
        this.energy = energy;
        this.balance = balance;
        this.count = count;
        this.playedColors = new int[count];
        this.unplayedColors = new int[count];
    }

    public static WaveformBars empty() {
        return new WaveformBars(new float[0], new float[0], new float[0], 0);
    }

    /**
     * Builds {@code barCount} bars from a waveform.
     *
     * <p>Bucket ranges are distributed with the same integer ratio used for the old
     * viewport columns, so the bars always cover the whole track exactly once. When there
     * are more bars than buckets the extra bars repeat the nearest bucket instead of
     * inventing silence.</p>
     */
    public static WaveformBars aggregate(WaveformMetrics.Waveform waveform, int barCount) {
        if (waveform == null || waveform.length() == 0 || barCount <= 0) {
            return empty();
        }
        int length = waveform.length();
        int bars = Math.max(1, Math.min(barCount, length));

        float[] amplitude = new float[bars];
        float[] energy = new float[bars];
        float[] balance = new float[bars];

        boolean bandsUsable = false;
        for (int i = 0; i < length; i++) {
            if (waveform.low[i] >= BAND_PRESENCE || waveform.high[i] >= BAND_PRESENCE) {
                bandsUsable = true;
                break;
            }
        }
        float gain = normalisationGain(waveform);

        float[] rawBalance = new float[bars];
        for (int bar = 0; bar < bars; bar++) {
            int from = (int) ((long) length * bar / bars);
            int to = (int) ((long) length * (bar + 1) / bars);
            if (to <= from) {
                to = Math.min(length, from + 1);
            }
            if (from >= length) {
                from = length - 1;
                to = length;
            }
            float peakSum = 0f;
            double energySum = 0.0;
            double lowSum = 0.0;
            double highSum = 0.0;
            int samples = 0;
            for (int i = from; i < to; i++) {
                peakSum += WaveformMetrics.safeUnit(waveform.peaks[i]);
                energySum += WaveformMetrics.safeUnit(waveform.rms[i]);
                lowSum += WaveformMetrics.safeUnit(waveform.low[i]);
                highSum += WaveformMetrics.safeUnit(waveform.high[i]);
                samples++;
            }
            int divisor = Math.max(1, samples);
            float normalised = Math.min(1f, (peakSum / divisor) * gain);
            amplitude[bar] = (float) Math.pow(normalised, AMPLITUDE_GAMMA);
            energy[bar] = (float) (energySum / divisor);
            rawBalance[bar] = bandsUsable
                    ? WaveformColor.bandBalanceFromDb(lowSum / divisor, highSum / divisor)
                    : 0f;
        }

        // One-column box filter smooths hue changes into a gradient.
        for (int bar = 0; bar < bars; bar++) {
            float sum = rawBalance[bar];
            int divisor = 1;
            if (bar > 0) {
                sum += rawBalance[bar - 1];
                divisor++;
            }
            if (bar < bars - 1) {
                sum += rawBalance[bar + 1];
                divisor++;
            }
            balance[bar] = sum / divisor;
        }

        WaveformBars result = new WaveformBars(amplitude, energy, balance, bars);
        for (int bar = 0; bar < bars; bar++) {
            result.playedColors[bar] = WaveformColor.color(amplitude[bar], energy[bar],
                    balance[bar], true);
            result.unplayedColors[bar] = WaveformColor.color(amplitude[bar], energy[bar],
                    balance[bar], false);
        }
        return result;
    }

    /**
     * Gain that maps the bucket peak p95 to 1.0. Uses a 1024-bin histogram so the cost is
     * independent of the track length and of the number of bars (zoom).
     */
    private static float normalisationGain(WaveformMetrics.Waveform waveform) {
        int length = waveform.length();
        int[] histogram = new int[PEAK_HISTOGRAM_BINS];
        for (int i = 0; i < length; i++) {
            float value = WaveformMetrics.safeUnit(waveform.peaks[i]);
            int bin = Math.min(PEAK_HISTOGRAM_BINS - 1,
                    (int) (value * PEAK_HISTOGRAM_BINS));
            histogram[bin]++;
        }
        // Standard percentile position (linear interpolation would only matter inside a bin).
        double target = (length - 1) * 0.95;
        int cumulative = 0;
        float p95 = 1f;
        for (int bin = 0; bin < PEAK_HISTOGRAM_BINS; bin++) {
            cumulative += histogram[bin];
            if (cumulative > target) {
                p95 = (bin + 0.5f) / PEAK_HISTOGRAM_BINS;
                break;
            }
        }
        if (!(p95 > 1e-4f) || Float.isNaN(p95)) {
            return 1f;
        }
        return Math.min(MAX_NORMALISATION_GAIN, 1f / p95);
    }

    /** Safe amplitude accessor (0 outside the array). */
    public float amplitudeAt(int index) {
        return index >= 0 && index < count ? amplitude[index] : 0f;
    }

    /** Safe energy accessor (0 outside the array). */
    public float energyAt(int index) {
        return index >= 0 && index < count ? energy[index] : 0f;
    }

    /** Safe balance accessor (0 outside the array). */
    public float balanceAt(int index) {
        return index >= 0 && index < count ? balance[index] : 0f;
    }

    /** Safe played-colour accessor (opaque neutral outside the array). */
    public int playedColorAt(int index) {
        return index >= 0 && index < count ? playedColors[index] : 0xFF93A1AF;
    }

    /** Safe upcoming-colour accessor (opaque neutral outside the array). */
    public int unplayedColorAt(int index) {
        return index >= 0 && index < count ? unplayedColors[index] : 0xFF93A1AF;
    }
}
