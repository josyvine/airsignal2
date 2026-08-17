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
import java.util.concurrent.atomic.AtomicBoolean;

public class AudioReceiver {

    private static final String TAG = "AudioReceiver";

    public static final int DEFAULT_SAMPLE_RATE = 44100;
    public static final byte SYNC_PREAMBLE = (byte) 0xAA;
    public static final byte START_FRAME_DELIMITER = (byte) 0x7E;

    // Standardized handshake command string to awaken and lock the remote receiver into Receiver Mode
    public static final String CMD_ACTIVATE_RECEIVER = "AIR_CMD:ACTIVATE_RECEIVER";

    private int baudRate = 1200; // 300, 600, 1200, 2400
    private int activeSampleRate = DEFAULT_SAMPLE_RATE;
    private final AtomicBoolean isListening = new AtomicBoolean(false);
    private AudioRecord audioRecord;
    private AudioReceiverListener listener;

    public interface AudioReceiverListener {
        void onByteDecoded(byte b);
        void onFrameDecoded(byte[] frameData);
        void onTokenDecoded(TemplateToken token);
        void onReceiverActivationCommand();
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
            public void onReceiverActivationCommand() {}

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

    public int getActiveSampleRate() {
        return activeSampleRate;
    }

    public boolean isListening() {
        return isListening.get();
    }

    @SuppressLint("MissingPermission")
    public void startListening() {
        if (isListening.get()) return;

        // Hardware Compatibility Probe Matrix
        int[] sampleRates = new int[]{44100, 48000, 16000, 8000};
        int[] audioSources = new int[]{
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.DEFAULT
        };

        boolean initialized = false;

        for (int source : audioSources) {
            for (int rate : sampleRates) {
                try {
                    int minBufferSize = AudioRecord.getMinBufferSize(
                            rate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT
                    );

                    if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                        continue;
                    }

                    int bufferSize = Math.max(minBufferSize * 4, 8192);

                    audioRecord = new AudioRecord(
                            source,
                            rate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufferSize
                    );

                    if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                        activeSampleRate = rate;
                        initialized = true;
                        AirLogger.i(TAG, "AudioRecord successfully initialized with Source=" + sourceToString(source) +
                                ", SampleRate=" + rate + " Hz, Baud=" + baudRate);
                        break;
                    } else {
                        audioRecord.release();
                        audioRecord = null;
                    }
                } catch (Exception e) {
                    if (audioRecord != null) {
                        try {
                            audioRecord.release();
                        } catch (Exception ignored) {}
                        audioRecord = null;
                    }
                }
            }
            if (initialized) break;
        }

        if (!initialized || audioRecord == null) {
            AirLogger.e(TAG, "AudioRecord failed to initialize across all hardware probe configurations.");
            if (listener != null) {
                listener.onError(new IllegalStateException("Microphone hardware probe failed across all sample rates."));
            }
            return;
        }

        try {
            isListening.set(true);
            audioRecord.startRecording();
            AirLogger.i(TAG, "AudioReceiver recording started actively.");
            new Thread(this::listenLoop).start();
        } catch (Exception e) {
            AirLogger.e(TAG, "Failed starting AudioRecord stream", e);
            if (listener != null) listener.onError(e);
            stopListening();
        }
    }

    private void listenLoop() {
        double samplesPerBit = (double) activeSampleRate / (double) baudRate;
        int bitSampleLen = Math.max((int) Math.round(samplesPerBit), 1);
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
                int bitVal = AudioDecoder.detectBit(bitBuffer, 0, read, activeSampleRate);

                if (bitVal == -1) {
                    // Ambient silence or voice chatter — skip
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

                        byte[] currentBufferBytes = frameBuffer.toByteArray();
                        String preview = new String(currentBufferBytes, StandardCharsets.UTF_8);

                        // 1. Check for remote ACTIVATE_RECEIVER acoustic handshake command
                        if (preview.contains(CMD_ACTIVATE_RECEIVER)) {
                            AirLogger.i(TAG, "Remote ACTIVATE_RECEIVER command detected over voice call!");
                            if (listener != null) {
                                listener.onReceiverActivationCommand();
                            }
                            isLockedOnPreamble = false;
                            frameBuffer.reset();
                            continue;
                        }

                        // 2. Mode 4 Check: If 16 bytes accumulated, attempt TemplateToken validation
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
                                continue;
                            }
                        }

                        // 3. Phonetic Image Preamble Check
                        if (preview.contains(PhoneticImageTransceiver.PHONETIC_IMG_PREAMBLE) && currentBufferBytes.length > 256) {
                            if (listener != null) {
                                listener.onFrameDecoded(currentBufferBytes);
                            }
                            isLockedOnPreamble = false;
                            frameBuffer.reset();
                            continue;
                        }

                        // 4. Mode 2/3 Raw Binary Packet Frame flush (263-byte packet or 512-byte flush)
                        if (frameBuffer.size() >= 263) {
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

    private String sourceToString(int source) {
        switch (source) {
            case MediaRecorder.AudioSource.VOICE_COMMUNICATION: return "VOICE_COMMUNICATION";
            case MediaRecorder.AudioSource.MIC: return "MIC";
            case MediaRecorder.AudioSource.DEFAULT: return "DEFAULT";
            default: return "SOURCE_" + source;
        }
    }
}