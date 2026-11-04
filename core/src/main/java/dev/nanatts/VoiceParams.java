package dev.nanatts;

import dev.nanatts.prosody.StyleProfile;

/** Immutable voice controls. All factors are relative to the voice's natural values (1.0 = default). */
public final class VoiceParams {

    private final double pitch;
    private final double range;
    private final double speed;
    private final double volume;
    private final double breathiness;
    private final double liveliness;
    private final StyleProfile style;

    private VoiceParams(double pitch, double range, double speed, double volume, double breathiness,
                        double liveliness, StyleProfile style) {
        this.pitch = pitch;
        this.range = range;
        this.speed = speed;
        this.volume = volume;
        this.breathiness = breathiness;
        this.liveliness = liveliness;
        this.style = style;
    }

    public static VoiceParams defaults() {
        return new VoiceParams(1.0, 1.0, 1.0, 1.0, 0.0, 1.0, StyleProfile.PLAYFUL);
    }

    /** Base pitch multiplier (1.0 = the recorded pitch of the voicebank). */
    public double pitch() {
        return pitch;
    }

    /** Intonation range multiplier (0 = monotone). */
    public double range() {
        return range;
    }

    /** Speaking rate multiplier (2.0 = twice as fast). */
    public double speed() {
        return speed;
    }

    /** Linear output gain. */
    public double volume() {
        return volume;
    }

    /** 0..1: blends noise into voiced sounds. */
    public double breathiness() {
        return breathiness;
    }

    /** Amount of random pitch drift (0 = perfectly smooth contours). */
    public double liveliness() {
        return liveliness;
    }

    /** Speaking style (intonation, rhythm, emphasis); default {@link StyleProfile#PLAYFUL}. */
    public StyleProfile style() {
        return style;
    }

    public VoiceParams style(StyleProfile s) {
        if (s == null) {
            throw new IllegalArgumentException("style must not be null");
        }
        return new VoiceParams(pitch, range, speed, volume, breathiness, liveliness, s);
    }

    public VoiceParams pitch(double v) {
        return new VoiceParams(positive(v, "pitch"), range, speed, volume, breathiness, liveliness, style);
    }

    public VoiceParams range(double v) {
        return new VoiceParams(pitch, Math.max(0, v), speed, volume, breathiness, liveliness, style);
    }

    public VoiceParams speed(double v) {
        return new VoiceParams(pitch, range, positive(v, "speed"), volume, breathiness, liveliness, style);
    }

    public VoiceParams volume(double v) {
        return new VoiceParams(pitch, range, speed, Math.max(0, v), breathiness, liveliness, style);
    }

    public VoiceParams breathiness(double v) {
        return new VoiceParams(pitch, range, speed, volume, Math.max(0, Math.min(1, v)), liveliness, style);
    }

    public VoiceParams liveliness(double v) {
        return new VoiceParams(pitch, range, speed, volume, breathiness, Math.max(0, v), style);
    }

    private static double positive(double v, String name) {
        if (!(v > 0)) {
            throw new IllegalArgumentException(name + " must be > 0");
        }
        return v;
    }
}
