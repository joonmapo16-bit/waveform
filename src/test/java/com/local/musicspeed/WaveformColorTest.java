package com.local.musicspeed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Colour rules: bass-dominant audio must land on a warm hue, treble-dominant audio on a cool
 * hue, louder buckets must be brighter, and the not-yet-played part must be darker than the
 * played part. Also covers the HSV→ARGB helper used instead of {@code Color.HSVToColor}.
 */
public class WaveformColorTest {

    @Test
    public void bassDominantIsWarmAndTrebleDominantIsCool() {
        float warm = WaveformColor.hue(1.0f, 0.05f);
        float cool = WaveformColor.hue(0.02f, 1.0f);
        float mid = WaveformColor.hue(0.5f, 0.5f);
        assertTrue("warm hue " + warm, warm >= 0f && warm <= 25f);
        assertTrue("cool hue " + cool, cool > 100f && cool <= 210f);
        assertTrue("mid hue between the two: " + mid, warm < mid && mid < cool);
        assertEquals(WaveformColor.HUE_MID, mid, 0.01f);
    }

    @Test
    public void warmColourHasMoreRedThanBlue() {
        int bass = WaveformColor.hsvToArgb(WaveformColor.hueForBalance(-1f), 0.86f, 0.9f);
        int treble = WaveformColor.hsvToArgb(WaveformColor.hueForBalance(1f), 0.86f, 0.9f);
        assertTrue("bass r=" + red(bass) + " b=" + blue(bass), red(bass) > blue(bass));
        assertTrue("treble r=" + red(treble) + " b=" + blue(treble), blue(treble) > red(treble));
    }

    @Test
    public void louderBucketIsBrighter() {
        int quiet = WaveformColor.playedColor(0.05f, 0.02f, 0.5f, 0.5f);
        int medium = WaveformColor.playedColor(0.4f, 0.4f, 0.5f, 0.5f);
        int loud = WaveformColor.playedColor(1f, 1f, 0.5f, 0.5f);
        assertTrue(luma(quiet) < luma(medium));
        assertTrue(luma(medium) < luma(loud));
        assertTrue(luma(loud) > 150f);
        assertTrue(luma(quiet) < 140f);
    }

    @Test
    public void upcomingPartIsDarkerThanPlayedPart() {
        int played = WaveformColor.playedColor(0.7f, 0.7f, 0.4f, 0.3f);
        int upcoming = WaveformColor.upcomingColor(0.7f, 0.7f, 0.4f, 0.3f);
        assertEquals(0xFF, alpha(played));
        assertEquals(0xFF, alpha(upcoming));
        assertTrue(luma(upcoming) < luma(played));
        // and it still keeps the same hue family
        assertTrue(hueDistance(played, upcoming) < 40f);
    }

    @Test
    public void bandBalanceReflectsLedBandsAndHueWrapsAtExtremes() {
        assertEquals(-1f, WaveformColor.bandBalance(1f, 0f), 1e-4f);
        assertEquals(1f, WaveformColor.bandBalance(0f, 1f), 1e-4f);
        assertEquals(0f, WaveformColor.bandBalance(0.5f, 0.5f), 1e-4f);
        // no band energy at all is "balanced" (neutral hue), never NaN
        assertEquals(0f, WaveformColor.bandBalance(0f, 0f), 1e-4f);
        assertEquals(0f, WaveformColor.bandBalance(-1f, -1f), 1e-4f);
        // NaN counts as "no energy in that band" after the 0..1 clamp
        assertEquals(1f, WaveformColor.bandBalance(Float.NaN, 0.5f), 1e-4f);
        assertEquals(-1f, WaveformColor.bandBalance(0.5f, Float.NaN), 1e-4f);
        // out-of-range inputs are clamped like the cache does
        assertEquals(1f, WaveformColor.bandBalance(0f, 5f), 1e-4f);
        // full bass clamps to the warm end, full treble to the cool end: hue itself stays in
        // 0..360 so it can be fed to HSV helpers directly.
        // hue is compressed toward mid; extremes are clamped, not wrapped
        assertTrue(WaveformColor.hueForBalance(-5f) >= WaveformColor.HUE_BASS - 1e-3f);
        assertTrue(WaveformColor.hueForBalance(5f) <= WaveformColor.HUE_TREBLE + 1e-3f);
        assertEquals(WaveformColor.HUE_MID, WaveformColor.hueForBalance(0f), 1e-3f);
        // 1.6 removes hue compression: +0.35 is already yellow-green (~93°).
        assertTrue(WaveformColor.hueForBalance(0.35f) > 85f);
        assertTrue(WaveformColor.hueForBalance(0.35f) < 105f);
        // a clearly bass-heavy bucket is warmer than a balanced one, a treble-heavy one cooler
        assertTrue(WaveformColor.hueForBalance(-0.8f) < WaveformColor.hueForBalance(0f));
        assertTrue(WaveformColor.hueForBalance(0.8f) > WaveformColor.hueForBalance(0f));
        // and hue() itself stays inside the documented warm..cool range for any input pair
        for (float a = 0f; a <= 1f; a += 0.1f) {
            for (float b = 0f; b <= 1f; b += 0.1f) {
                float hue = WaveformColor.hue(a, b);
                assertTrue("hue out of range for " + a + "/" + b + ": " + hue,
                        hue >= WaveformColor.HUE_BASS - 1e-3f && hue <= WaveformColor.HUE_TREBLE + 1e-3f);
            }
        }
        // legacy sqrt-scale helper (kept for API compatibility, no longer used by bars)
        assertEquals(1f, WaveformColor.bandBalanceFromSqrt(0.0, 1.0), 1e-4f);
        assertEquals(0.5f, WaveformColor.bandBalanceFromSqrt(0.25, 0.75), 1e-4f);
        assertEquals(0f, WaveformColor.bandBalanceFromSqrt(0.0, 0.0), 1e-4f);
    }

    @Test
    public void boundaryValuesNeverProduceNaN() {
        float[] values = {0f, 1f, -1f, Float.NaN, Float.POSITIVE_INFINITY, 0.5f};
        for (float a : values) {
            for (float b : values) {
                for (float c : values) {
                    for (float d : values) {
                        int argb = WaveformColor.color(a, b, c, d, true);
                        assertTrue(argb != 0);
                        assertEquals(0xFF, alpha(argb));
                        float hue = WaveformColor.hue(a, c);
                        assertFalse(Float.isNaN(hue));
                        assertTrue(WaveformColor.hsvToArgb(hue, 0.5f, 0.5f) != 0);
                        float value = WaveformColor.valueForEnergy(b, false);
                        assertFalse(Float.isNaN(value));
                        assertTrue(value >= 0f && value <= 1f);
                    }
                }
            }
        }
    }

    @Test
    public void dbBandRatioReachesRedAndCyan() {
        // 24 dB below the other band (well past the +/-12 dB span) must saturate.
        float lowOnly = (float) Math.pow(10.0, -24.0 / 20.0);
        float bassHue = WaveformColor.hue(1f, lowOnly);
        float trebleHue = WaveformColor.hue(lowOnly, 1f);
        assertTrue("bass hue " + bassHue, bassHue <= 20f);
        assertTrue("treble hue " + trebleHue, trebleHue >= 180f);
        assertEquals(1f, WaveformColor.HUE_COMPRESSION, 1e-6f);

        // Exactly +/-12 dB is the clamp boundary; anything beyond stays clamped.
        float twelveBelow = (float) Math.pow(10.0, -WaveformColor.BALANCE_DB_SPAN / 20.0);
        assertEquals(-1f, WaveformColor.bandBalanceFromDb(1f, twelveBelow), 1e-3f);
        assertEquals(1f, WaveformColor.bandBalanceFromDb(twelveBelow, 1f), 1e-3f);
        assertEquals(-1f, WaveformColor.bandBalanceFromDb(1f, 0f), 1e-4f);
        assertEquals(1f, WaveformColor.bandBalanceFromDb(0f, 1f), 1e-4f);
        // NaN inputs become digital silence, never NaN
        assertEquals(0f, WaveformColor.bandBalanceFromDb(Float.NaN, Float.NaN), 1e-4f);
        assertFalse(Float.isNaN(WaveformColor.bandBalanceFromDb(Float.NaN, 1f)));
    }

    @Test
    public void unplayedPartStaysVisibleButClearlyDimmer() {
        float[] energies = {0f, 0.05f, 0.25f, 0.6f, 1f};
        for (float energy : energies) {
            float played = WaveformColor.valueForEnergy(energy, true);
            float unplayed = WaveformColor.valueForEnergy(energy, false);
            assertTrue("unplayed floor at e=" + energy, unplayed >= 0.28f - 1e-4f);
            float contrast = (played - unplayed) / Math.max(played, 1e-4f);
            assertTrue("played/unplayed contrast at e=" + energy + ": " + contrast,
                    contrast >= 0.25f);
        }
    }

    @Test
    public void hsvHelperMatchesKnownColours() {
        assertEquals(0xFFFF0000, WaveformColor.hsvToArgb(0f, 1f, 1f));
        assertEquals(0xFF00FF00, WaveformColor.hsvToArgb(120f, 1f, 1f));
        assertEquals(0xFF0000FF, WaveformColor.hsvToArgb(240f, 1f, 1f));
        assertEquals(0xFF000000, WaveformColor.hsvToArgb(0f, 0f, 0f));
        assertEquals(0xFFFFFFFF, WaveformColor.hsvToArgb(0f, 0f, 1f));
        // hue wraps around
        assertEquals(WaveformColor.hsvToArgb(30f, 0.5f, 0.5f), WaveformColor.hsvToArgb(390f, 0.5f, 0.5f));
        assertEquals(WaveformColor.hsvToArgb(330f, 0.5f, 0.5f), WaveformColor.hsvToArgb(-30f, 0.5f, 0.5f));
        // saturations/values outside 0..1 are clamped, NaN becomes black
        assertEquals(0xFFFFFFFF, WaveformColor.hsvToArgb(0f, -1f, 5f));
        assertEquals(0xFF000000, WaveformColor.hsvToArgb(Float.NaN, Float.NaN, Float.NaN));
    }

    private static int red(int argb) {
        return (argb >> 16) & 0xFF;
    }

    private static int green(int argb) {
        return (argb >> 8) & 0xFF;
    }

    private static int blue(int argb) {
        return argb & 0xFF;
    }

    private static int alpha(int argb) {
        return (argb >>> 24) & 0xFF;
    }

    private static float luma(int argb) {
        return 0.2126f * red(argb) + 0.7152f * green(argb) + 0.0722f * blue(argb);
    }

    /** Rough hue difference in degrees (0..180). */
    private static float hueDistance(int first, int second) {
        float h1 = hueOf(first);
        float h2 = hueOf(second);
        float diff = Math.abs(h1 - h2) % 360f;
        return Math.min(diff, 360f - diff);
    }

    private static float hueOf(int argb) {
        float r = red(argb) / 255f;
        float g = green(argb) / 255f;
        float b = blue(argb) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;
        if (delta <= 1e-5f) {
            return 0f;
        }
        float hue;
        if (max == r) {
            hue = 60f * (((g - b) / delta) % 6f);
        } else if (max == g) {
            hue = 60f * (((b - r) / delta) + 2f);
        } else {
            hue = 60f * (((r - g) / delta) + 4f);
        }
        if (hue < 0f) {
            hue += 360f;
        }
        return hue;
    }
}
