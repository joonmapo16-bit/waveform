package com.local.musicspeed;

/**
 * DJ-style colouring for the waveform bars: hue from the low/high energy balance, brightness
 * from the RMS energy, and a played/unplayed pair (played = vivid, upcoming = muted).
 *
 * <p>Pure Java on purpose: the HSV→RGB conversion and the "energy → brightness" curve are
 * unit tested on the JVM, and the Android view only turns the resulting ARGB ints into
 * {@code Paint} colours. Keeping the maths separate also means the colour rules can be tuned
 * without touching {@link WaveformView}.</p>
 */
public final class WaveformColor {

    /** Bass-dominant audio (red/orange). */
    public static final float HUE_BASS = 6f;
    /** Balanced mid content. */
    public static final float HUE_MID = 36f;
    /** Treble-dominant audio (cyan/blue). */
    public static final float HUE_TREBLE = 198f;

    public static final float SATURATION = 0.86f;

    /**
     * 1.0 since 1.6: the dB band ratio already provides the contrast, so the hue range is no
     * longer compressed. Bass really reaches {@link #HUE_BASS} and treble {@link #HUE_TREBLE}
     * (red ↔ cyan); 1.5's 0.55 compression made cyan structurally unreachable.
     */
    public static final float HUE_COMPRESSION = 1.0f;

    /** Balance is full-scale when the high band is 12 dB above the low band (and vice versa). */
    public static final float BALANCE_DB_SPAN = 12.0f;

    /** -120 dB floor for the band ratio (keeps log10 finite for digital silence). */
    private static final double MIN_BAND_LEVEL = 1e-6;

    /** Brightness of the played part at zero and full energy. */
    private static final float PLAYED_MIN_VALUE = 0.42f;
    private static final float PLAYED_MAX_VALUE = 1.0f;
    /** The not-yet-played part is dimmer so the playback position reads at a glance. */
    private static final float UNPLAYED_VALUE_SCALE = 0.55f;
    private static final float UNPLAYED_MIN_VALUE = 0.28f;
    private static final float UNPLAYED_SATURATION_SCALE = 0.78f;

    private WaveformColor() {
    }

    /** Low/high balance in -1..1: -1 = all bass, 0 = balanced, +1 = all treble. */
    public static float bandBalance(float low, float high) {
        return bandBalanceFromDb(low, high);
    }

    /**
     * Balance from linear band energies, expressed as a dB difference over
     * {@link #BALANCE_DB_SPAN}. A 12 dB difference already saturates the hue, so ordinary
     * tracks show real red/cyan stretches instead of staying in orange/green.
     */
    public static float bandBalanceFromDb(double meanLow, double meanHigh) {
        double l = WaveformMetrics.safeUnit(meanLow);
        double h = WaveformMetrics.safeUnit(meanHigh);
        double lowDb = 20.0 * Math.log10(Math.max(l, MIN_BAND_LEVEL));
        double highDb = 20.0 * Math.log10(Math.max(h, MIN_BAND_LEVEL));
        double balance = (highDb - lowDb) / BALANCE_DB_SPAN;
        if (Double.isNaN(balance)) {
            return 0f;
        }
        return (float) Math.max(-1.0, Math.min(1.0, balance));
    }

    /** Hue in degrees: bass → warm, treble → cool, mid → yellow/teal between them. */
    public static float hue(float low, float high) {
        return hueForBalance(bandBalance(low, high));
    }

    /**
     * Hue for a precomputed balance (-1 = all bass .. +1 = all treble). The balance is
     * already saturated at +/-12 dB by {@link #bandBalanceFromDb(double, double)}.
     */
    public static float hueForBalance(float balance) {
        if (Float.isNaN(balance)) {
            return HUE_MID;
        }
        float clamped = Math.max(-1f, Math.min(1f, balance)) * HUE_COMPRESSION;
        if (clamped < 0f) {
            // bass side: HUE_BASS (warm) .. HUE_MID
            return HUE_BASS + (HUE_MID - HUE_BASS) * (1f + clamped);
        }
        // treble side: HUE_MID .. HUE_TREBLE (through green/teal)
        return HUE_MID + (HUE_TREBLE - HUE_MID) * clamped;
    }

    /**
     * Balance from already-averaged band energies on a compressed (sqrt) scale, matching how
     * the arrays are normalised. Averaging happens before clipping, so a quiet treble band
     * next to a loud bass band keeps most of its contrast instead of being clamped away.
     */
    public static float bandBalanceFromSqrt(double meanSqrtLow, double meanSqrtHigh) {
        double l = WaveformMetrics.safeUnit(meanSqrtLow);
        double h = WaveformMetrics.safeUnit(meanSqrtHigh);
        double total = l + h;
        if (!(total > 1e-6)) {
            return 0f;
        }
        double balance = (h - l) / total;
        if (Double.isNaN(balance)) {
            return 0f;
        }
        return (float) Math.max(-1.0, Math.min(1.0, balance));
    }

    /** Brightness curve: low energy is dark, high energy approaches white-hot. */
    public static float valueForEnergy(float energy, boolean played) {
        float e = WaveformMetrics.safeUnit(energy);
        // sqrt gives quiet passages some visible light instead of a flat dark band.
        float shaped = (float) Math.sqrt(e);
        float value = PLAYED_MIN_VALUE + (PLAYED_MAX_VALUE - PLAYED_MIN_VALUE) * shaped;
        if (played) {
            return clamp01(value);
        }
        float dim = value * UNPLAYED_VALUE_SCALE;
        return clamp01(Math.max(UNPLAYED_MIN_VALUE, dim));
    }

    public static float saturationFor(boolean played) {
        return played ? SATURATION : SATURATION * UNPLAYED_SATURATION_SCALE;
    }

    /**
     * Full ARGB colour for one bucket.
     *
     * @param peak     amplitude 0..1 (bar height; used to fall back when RMS is 0)
     * @param rms      energy 0..1 (brightness)
     * @param balance  low/high balance in -1..1 (see {@link #bandBalance}); NaN → neutral
     * @param played   true for the already-played part (vivid) / false for upcoming (dim)
     */
    public static int color(float peak, float rms, float balance, boolean played) {
        float energy = WaveformMetrics.safeUnit(rms);
        if (energy <= 0f) {
            energy = WaveformMetrics.safeUnit(peak) * 0.35f;
        }
        return hsvToArgb(hueForBalance(balance), saturationFor(played), valueForEnergy(energy, played));
    }

    /** Convenience overload computing the balance from normalised band arrays. */
    public static int color(float peak, float rms, float low, float high, boolean played) {
        return color(peak, rms, bandBalance(low, high), played);
    }

    /** Played colour: one 0xFF alpha step at a time (used by the {@code #RRGGBB} cache). */
    public static int playedColor(float peak, float rms, float low, float high) {
        return color(peak, rms, low, high, true);
    }

    /** Not-yet-played colour. */
    public static int upcomingColor(float peak, float rms, float low, float high) {
        return color(peak, rms, low, high, false);
    }

    /**
     * Standalone HSV→ARGB (a=255). Android's {@code Color.HSVToColor} exists, but writing the
     * conversion here keeps the colour rules testable without Robolectric.
     *
     * @param hueDegrees any real number, wrapped into 0..360
     * @param saturation  0..1 (clamped, NaN → 0)
     * @param value       0..1 (clamped, NaN → 0)
     */
    public static int hsvToArgb(float hueDegrees, float saturation, float value) {
        float h = Float.isNaN(hueDegrees) ? 0f : hueDegrees % 360f;
        if (h < 0f) {
            h += 360f;
        }
        float s = Float.isNaN(saturation) ? 0f : clamp01(saturation);
        float v = Float.isNaN(value) ? 0f : clamp01(value);
        float c = v * s;
        float hp = h / 60f;
        float x = c * (1f - Math.abs((hp % 2f) - 1f));
        float r;
        float g;
        float b;
        if (hp < 1f) {
            r = c;
            g = x;
            b = 0f;
        } else if (hp < 2f) {
            r = x;
            g = c;
            b = 0f;
        } else if (hp < 3f) {
            r = 0f;
            g = c;
            b = x;
        } else if (hp < 4f) {
            r = 0f;
            g = x;
            b = c;
        } else if (hp < 5f) {
            r = x;
            g = 0f;
            b = c;
        } else {
            r = c;
            g = 0f;
            b = x;
        }
        float m = v - c;
        int ri = toByte(r + m);
        int gi = toByte(g + m);
        int bi = toByte(b + m);
        return 0xFF000000 | (ri << 16) | (gi << 8) | bi;
    }

    private static int toByte(float component) {
        int scaled = Math.round(component * 255f);
        return Math.max(0, Math.min(255, scaled));
    }

    private static float clamp01(float value) {
        if (Float.isNaN(value) || value <= 0f) {
            return 0f;
        }
        return value >= 1f ? 1f : value;
    }
}
