package com.example.audio;

public class AudioDecoder {

    // Goertzel algorithm to detect Mark (1200Hz) vs Space (2200Hz) frequency in PCM buffer
    public static int detectBit(short[] pcmBuffer, int sampleRate) {
        double markMag = goertzel(pcmBuffer, ToneGenerator.MARK_FREQ, sampleRate);
        double spaceMag = goertzel(pcmBuffer, ToneGenerator.SPACE_FREQ, sampleRate);

        return (markMag > spaceMag) ? 1 : 0;
    }

    private static double goertzel(short[] pcm, double targetFreq, int sampleRate) {
        int n = pcm.length;
        double k = Math.round(n * targetFreq / sampleRate);
        double w = (2.0 * Math.PI * k) / n;
        double cosine = Math.cos(w);
        double coeff = 2.0 * cosine;

        double q0 = 0.0;
        double q1 = 0.0;
        double q2 = 0.0;

        for (short sample : pcm) {
            q0 = coeff * q1 - q2 + sample;
            q2 = q1;
            q1 = q0;
        }

        return (q1 * q1 + q2 * q2 - q1 * q2 * coeff);
    }
}
