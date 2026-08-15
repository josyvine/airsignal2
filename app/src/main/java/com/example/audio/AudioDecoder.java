package com.example.audio;

import com.example.models.TemplateToken;
import com.example.utils.AirLogger;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

public class AudioDecoder {

    private static final String TAG = "AudioDecoder";

    public static final int MARK_FREQ = 1200;   // Bit 1 (Hz)
    public static final int SPACE_FREQ = 2200;  // Bit 0 (Hz)

    public static final byte SYNC_PREAMBLE = (byte) 0xAA;
    public static final byte START_FRAME_DELIMITER = (byte) 0x7E;

    // Minimum RMS energy threshold to distinguish signal from silence
    private static final double MIN_ENERGY_THRESHOLD = 500.0;

    /**
     * Legacy backward-compatible bit detector.
     */
    public static int detectBit(short[] pcmBuffer, int sampleRate) {
        if (pcmBuffer == null || pcmBuffer.length == 0) return 0;
        return detectBit(pcmBuffer, 0, pcmBuffer.length, sampleRate);
    }

    /**
     * High-speed sub-array bit detector avoiding memory allocation.
     */
    public static int detectBit(short[] pcm, int offset, int length, int sampleRate) {
        if (pcm == null || length <= 0 || offset + length > pcm.length) {
            return 0;
        }

        double totalEnergy = calculateRmsEnergy(pcm, offset, length);
        if (totalEnergy < MIN_ENERGY_THRESHOLD) {
            return -1; // Silence or background noise
        }

        double markPower = calculateGoertzelPower(pcm, offset, length, MARK_FREQ, sampleRate);
        double spacePower = calculateGoertzelPower(pcm, offset, length, SPACE_FREQ, sampleRate);

        return (markPower > spacePower) ? 1 : 0;
    }

    /**
     * Demodulates a continuous PCM audio buffer into a list of raw data bytes,
     * stripping synchronization preambles automatically.
     */
    public static byte[] decodeFrameFromPcm(short[] pcmStream, int sampleRate, int baudRate) {
        if (pcmStream == null || pcmStream.length == 0 || baudRate <= 0) {
            return new byte[0];
        }

        double samplesPerBit = (double) sampleRate / (double) baudRate;
        int totalBits = (int) (pcmStream.length / samplesPerBit);

        if (totalBits < 8) return new byte[0];

        List<Integer> rawBits = new ArrayList<>();

        for (int b = 0; b < totalBits; b++) {
            int offset = (int) Math.round(b * samplesPerBit);
            int len = (int) Math.round((b + 1) * samplesPerBit) - offset;

            if (offset + len <= pcmStream.length) {
                int bitVal = detectBit(pcmStream, offset, len, sampleRate);
                if (bitVal != -1) {
                    rawBits.add(bitVal);
                } else {
                    rawBits.add(0);
                }
            }
        }

        // Reconstruct bytes from bitstream
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        int currentByte = 0;
        int bitCount = 0;

        for (int bit : rawBits) {
            currentByte = (currentByte << 1) | (bit & 1);
            bitCount++;

            if (bitCount == 8) {
                byteStream.write((byte) (currentByte & 0xFF));
                currentByte = 0;
                bitCount = 0;
            }
        }

        byte[] allBytes = byteStream.toByteArray();
        return extractPayloadFromFramedBytes(allBytes);
    }

    /**
     * Mode 4: Directly extracts and validates a 16-byte TemplateToken from raw PCM audio.
     */
    public static TemplateToken decodeTokenFromPcm(short[] pcmStream, int sampleRate, int baudRate) {
        byte[] payload = decodeFrameFromPcm(pcmStream, sampleRate, baudRate);
        if (payload == null || payload.length < TemplateToken.TOKEN_BYTE_SIZE) {
            return null;
        }

        // Locate valid 16-byte token slice with matching CRC16
        for (int i = 0; i <= payload.length - TemplateToken.TOKEN_BYTE_SIZE; i++) {
            byte[] candidate = new byte[TemplateToken.TOKEN_BYTE_SIZE];
            System.arraycopy(payload, i, candidate, 0, TemplateToken.TOKEN_BYTE_SIZE);

            TemplateToken token = TemplateToken.fromByteArray(candidate);
            if (token != null && token.isValid()) {
                AirLogger.i(TAG, "Successfully demodulated valid TemplateToken ID=" + token.getTemplateId());
                return token;
            }
        }

        return null;
    }

    /**
     * Hunts for sync preamble (0xAA 0xAA 0xAA 0x7E) and extracts the enclosed payload.
     */
    private static byte[] extractPayloadFromFramedBytes(byte[] rawBytes) {
        if (rawBytes == null || rawBytes.length < 4) {
            return rawBytes != null ? rawBytes : new byte[0];
        }

        int syncIndex = -1;
        for (int i = 0; i < rawBytes.length - 1; i++) {
            if (rawBytes[i] == START_FRAME_DELIMITER) {
                syncIndex = i + 1;
                break;
            }
        }

        if (syncIndex != -1 && syncIndex < rawBytes.length) {
            byte[] payload = new byte[rawBytes.length - syncIndex];
            System.arraycopy(rawBytes, syncIndex, payload, 0, payload.length);
            return payload;
        }

        return rawBytes;
    }

    /**
     * Single-bin discrete Fourier transform (Goertzel Algorithm).
     */
    public static double calculateGoertzelPower(short[] pcm, int offset, int length, double targetFreq, int sampleRate) {
        double k = Math.round(((double) length * targetFreq) / (double) sampleRate);
        double omega = (2.0 * Math.PI * k) / (double) length;
        double cosine = Math.cos(omega);
        double coeff = 2.0 * cosine;

        double q0 = 0.0;
        double q1 = 0.0;
        double q2 = 0.0;

        for (int i = offset; i < offset + length; i++) {
            q0 = coeff * q1 - q2 + (double) pcm[i];
            q2 = q1;
            q1 = q0;
        }

        return (q1 * q1 + q2 * q2 - q1 * q2 * coeff);
    }

    /**
     * Computes RMS energy of a PCM buffer segment.
     */
    public static double calculateRmsEnergy(short[] pcm, int offset, int length) {
        if (length <= 0) return 0.0;
        double sum = 0.0;
        for (int i = offset; i < offset + length; i++) {
            sum += (double) pcm[i] * (double) pcm[i];
        }
        return Math.sqrt(sum / (double) length);
    }
}