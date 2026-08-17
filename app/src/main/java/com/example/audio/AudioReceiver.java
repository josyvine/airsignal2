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

    public static final int MAX_STREAM_BUFFER_SIZE = 32768; // 32 KB maximum image/file buffer

    // Standardized handshake command strings
    public static final String CMD_ACTIVATE_RECEIVER = "AIR_CMD:ACTIVATE_RECEIVER";
    public static final String CMD_RECEIVER_READY = "AIR_ACK:RECEIVER_READY";

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
        void onReceiverReadyAckReceived();
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
            public void onReceiverReadyAckReceived() {}

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
        int consecutiveSilenceCount = 0;

        // Frame Detection State Machine
        boolean isLockedOnPreamble = false;
        boolean isAccumulatingImage = false;
        ByteArrayOutputStream frameBuffer = new ByteArrayOutputStream();

        while (isListening.get()) {
            if (audioRecord == null || audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                break;
            }

            int read = audioRecord.read(bitBuffer, 0, bitBuffer.length);
            if (read > 0) {
                int bitVal = AudioDecoder.detectBit(bitBuffer, 0, read, activeSampleRate);

                if (bitVal == -1) {
                    consecutiveSilenceCount++;

                    // If we accumulated an image stream and tone silence is reached (end of transmission), deliver full stream
                    if (isAccumulatingImage && frameBuffer.size() > 50 && consecutiveSilenceCount > 30) {
                        byte[] fullStreamBytes = frameBuffer.toByteArray();
                        AirLogger.i(TAG, "End of audio transmission detected via silence interval. Delivering full Phonetic Image (" + fullStreamBytes.length + " bytes).");
                        if (listener != null) {
                            listener.onFrameDecoded(fullStreamBytes);
                        }
                        isLockedOnPreamble = false;
                        isAccumulatingImage = false;
                        consecutiveSilenceCount = 0;
                        frameBuffer.reset();
                    }
                    continue;
                }

                consecutiveSilenceCount = 0;
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
                            isAccumulatingImage = false;
                            frameBuffer.reset();
                        }
                    } else {
                        frameBuffer.write(completedByte);

                        byte[] currentBufferBytes = frameBuffer.toByteArray();
                        String preview = new String(currentBufferBytes, StandardCharsets.UTF_8);

                        // 1. Check for remote RECEIVER_READY ACK (Sender side)
                        if (!isAccumulatingImage && preview.contains(CMD_RECEIVER_READY)) {
                            AirLogger.i(TAG, "Acoustic AIR_ACK:RECEIVER_READY detected! Remote receiver answered and listening.");
                            if (listener != null) {
                                listener.onReceiverReadyAckReceived();
                            }
                            isLockedOnPreamble = false;
                            frameBuffer.reset();
                            continue;
                        }

                        // 2. Check for remote ACTIVATE_RECEIVER acoustic handshake command (Receiver side)
                        if (!isAccumulatingImage && preview.contains(CMD_ACTIVATE_RECEIVER)) {
                            AirLogger.i(TAG, "Remote ACTIVATE_RECEIVER command detected over voice call!");
                            if (listener != null) {
                                listener.onReceiverActivationCommand();
                            }
                            isLockedOnPreamble = false;
                            frameBuffer.reset();
                            continue;
                        }

                        // 3. Mode 4 Check: If 16 bytes accumulated, attempt TemplateToken validation
                        if (!isAccumulatingImage && frameBuffer.size() == TemplateToken.TOKEN_BYTE_SIZE) {
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

                        // 4. Phonetic Image Preamble Check & Full Stream Accumulator (Up to 32 KB)
                        if (preview.contains(PhoneticImageTransceiver.PHONETIC_IMG_PREAMBLE)) {
                            isAccumulatingImage = true;

                            // Check if stream reached the trailing closure delimiters (contains total tokens & original length closures)
                            int firstHash = preview.indexOf('#');
                            int lastHash = preview.lastIndexOf('#');
                            if (firstHash != -1 && lastHash > firstHash && (preview.endsWith("#") || countOccurrences(preview, '#') >= 3)) {
                                AirLogger.i(TAG, "Complete Phonetic Image stream accumulated (" + currentBufferBytes.length + " bytes). Delivering.");
                                if (listener != null) {
                                    listener.onFrameDecoded(currentBufferBytes);
                                }
                                isLockedOnPreamble = false;
                                isAccumulatingImage = false;
                                frameBuffer.reset();
                                continue;
                            }
                        }

                        // 5. Mode 2/3 Raw Binary Packet Frame flush (Binary packets start with 0x53 'S' and are 263 bytes)
                        if (!isAccumulatingImage && frameBuffer.size() >= 263) {
                            if (currentBufferBytes[0] == 0x53 || containsBinaryHeader(currentBufferBytes)) {
                                if (listener != null) {
                                    listener.onFrameDecoded(currentBufferBytes);
                                }
                                isLockedOnPreamble = false;
                                frameBuffer.reset();
                            } else if (frameBuffer.size() >= MAX_STREAM_BUFFER_SIZE) {
                                // Safety limit flush
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
        }

        // Flush remaining frame if stream ended
        if (frameBuffer.size() > 0 && listener != null) {
            listener.onFrameDecoded(frameBuffer.toByteArray());
        }
    }

    private int countOccurrences(String str, char ch) {
        int count = 0;
        for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) == ch) count++;
        }
        return count;
    }

    private boolean containsBinaryHeader(byte[] data) {
        if (data == null || data.length < 7) return false;
        for (int i = 0; i <= data.length - 7; i++) {
            if (data[i] == 0x53) return true;
        }
        return false;
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