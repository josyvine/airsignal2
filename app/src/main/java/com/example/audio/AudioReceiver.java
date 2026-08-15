package com.example.audio;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import com.example.knowledge.PhoneticImageTransceiver;
import com.example.models.TemplateToken;
import com.example.utils.AirLogger;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class AudioReceiver {

    private static final String TAG = "AudioReceiver";

    public static final int SAMPLE_RATE = 44100;
    public static final byte SYNC_PREAMBLE = (byte) 0xAA;
    public static final byte START_FRAME_DELIMITER = (byte) 0x7E;

    private int baudRate = 1200; // 300, 600, 1200, 2400
    private final AtomicBoolean isListening = new AtomicBoolean(false);
    private AudioRecord audioRecord;
    private AudioReceiverListener listener;

    public interface AudioReceiverListener {
        void onByteDecoded(byte b);
        void onFrameDecoded(byte[] frameData);
        void onTokenDecoded(TemplateToken token);
        void onError(Exception e);
    }

    // Legacy listener interface for backward compatibility
    public interface AudioDecoderListener {
        void onByteDecoded(byte b);
    }

    public AudioReceiver(AudioDecoderListener legacyListener) {
        this.listener = new AudioReceiverListener() {
            @Override
            public void onByteDecoded(byte b) {
                if (legacyListener != null) legacyListener.onByteDecoded(b);
            }

            @Override
            public void onFrameDecoded(byte[] frameData) {}

            @Override
            public void onTokenDecoded(TemplateToken token) {}

            @Override
            public void onError(Exception e) {}
        };
    }

    public AudioReceiver(AudioReceiverListener listener) {
        this.listener = listener;
    }

    public void setBaudRate(int baudRate) {
        if (baudRate > 0) {
            this.baudRate = baudRate;
        }
    }

    public int getBaudRate() {
        return baudRate;
    }

    public boolean isListening() {
        return isListening.get();
    }

    @SuppressLint("MissingPermission")
    public void startListening() {
        if (isListening.get()) return;

        int minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );

        int bufferSize = Math.max(minBufferSize * 4, 8192);

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                AirLogger.e(TAG, "AudioRecord failed to initialize");
                if (listener != null) listener.onError(new IllegalStateException("AudioRecord initialization failed"));
                return;
            }

            isListening.set(true);
            audioRecord.startRecording();
            AirLogger.i(TAG, "AudioReceiver started listening on VOICE_COMMUNICATION at " + baudRate + " Baud");

            new Thread(this::listenLoop).start();
        } catch (Exception e) {
            AirLogger.e(TAG, "Failed starting AudioReceiver", e);
            if (listener != null) listener.onError(e);
            stopListening();
        }
    }

    private void listenLoop() {
        double samplesPerBit = (double) SAMPLE_RATE / (double) baudRate;
        int bitSampleLen = (int) Math.round(samplesPerBit);
        short[] bitBuffer = new short[bitSampleLen];

        int currentByteAccumulator = 0;
        int bitCount = 0;

        // Frame Detection State Machine
        boolean isLockedOnPreamble = false;
        ByteArrayOutputStream frameBuffer = new ByteArrayOutputStream();

        while (isListening.get()) {
            if (audioRecord == null || audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                break;
            }

            int read = audioRecord.read(bitBuffer, 0, bitBuffer.length);
            if (read > 0) {
                int bitVal = AudioDecoder.detectBit(bitBuffer, 0, read, SAMPLE_RATE);

                if (bitVal == -1) {
                    // Silence or room noise
                    continue;
                }

                currentByteAccumulator = (currentByteAccumulator << 1) | (bitVal & 1);
                bitCount++;

                if (bitCount == 8) {
                    byte completedByte = (byte) (currentByteAccumulator & 0xFF);
                    currentByteAccumulator = 0;
                    bitCount = 0;

                    if (listener != null) {
                        listener.onByteDecoded(completedByte);
                    }

                    // Process Frame Preamble & Delimiter (0xAA ... 0x7E)
                    if (!isLockedOnPreamble) {
                        if (completedByte == START_FRAME_DELIMITER) {
                            isLockedOnPreamble = true;
                            frameBuffer.reset();
                        }
                    } else {
                        frameBuffer.write(completedByte);

                        // 1. Mode 4 Check: If 16 bytes accumulated, attempt TemplateToken validation
                        if (frameBuffer.size() == TemplateToken.TOKEN_BYTE_SIZE) {
                            byte[] candidateBytes = frameBuffer.toByteArray();
                            TemplateToken token = TemplateToken.fromByteArray(candidateBytes);

                            if (token != null && token.isValid()) {
                                AirLogger.i(TAG, "Mode 4 Token detected automatically! ID=" + token.getTemplateId());
                                if (listener != null) {
                                    listener.onTokenDecoded(token);
                                }
                                isLockedOnPreamble = false;
                                frameBuffer.reset();
                            }
                        } 
                        
                        // 2. Phonetic Image Preamble Check
                        byte[] currentBufferBytes = frameBuffer.toByteArray();
                        String preview = new String(currentBufferBytes, StandardCharsets.UTF_8);
                        if (preview.contains(PhoneticImageTransceiver.PHONETIC_IMG_PREAMBLE) && currentBufferBytes.length > 256) {
                            if (listener != null) {
                                listener.onFrameDecoded(currentBufferBytes);
                            }
                            isLockedOnPreamble = false;
                            frameBuffer.reset();
                        } else if (frameBuffer.size() > 512) {
                            // Mode 2/3 Raw Packet Frame flush
                            if (listener != null) {
                                listener.onFrameDecoded(currentBufferBytes);
                            }
                            isLockedOnPreamble = false;
                            frameBuffer.reset();
                        }
                    }
                }
            }
        }

        // Flush remaining frame if stream ended
        if (frameBuffer.size() > 0 && listener != null) {
            listener.onFrameDecoded(frameBuffer.toByteArray());
        }
    }

    public void stopListening() {
        isListening.set(false);
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
                audioRecord.release();
            } catch (Exception e) {
                AirLogger.e(TAG, "Error releasing AudioRecord", e);
            } finally {
                audioRecord = null;
            }
        }
        AirLogger.i(TAG, "AudioReceiver stopped listening");
    }
}