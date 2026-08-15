package com.example.audio;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

public class AudioReceiver {

    private boolean isListening = false;
    private AudioRecord audioRecord;
    private AudioDecoderListener listener;

    public interface AudioDecoderListener {
        void onByteDecoded(byte b);
    }

    public AudioReceiver(AudioDecoderListener listener) {
        this.listener = listener;
    }

    @SuppressLint("MissingPermission")
    public void startListening() {
        if (isListening) return;

        int bufferSize = AudioRecord.getMinBufferSize(
                ToneGenerator.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
        );

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                ToneGenerator.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
        );

        isListening = true;
        audioRecord.startRecording();

        new Thread(new Runnable() {
            @Override
            public void run() {
                short[] pcmBuffer = new short[bufferSize / 2];
                int bitCount = 0;
                byte currentByte = 0;

                while (isListening) {
                    int read = audioRecord.read(pcmBuffer, 0, pcmBuffer.length);
                    if (read > 0) {
                        int bit = AudioDecoder.detectBit(pcmBuffer, ToneGenerator.SAMPLE_RATE);
                        currentByte = (byte) ((currentByte << 1) | bit);
                        bitCount++;

                        if (bitCount == 8) {
                            if (listener != null) {
                                listener.onByteDecoded(currentByte);
                            }
                            bitCount = 0;
                            currentByte = 0;
                        }
                    }
                }
            }
        }).start();
    }

    public void stopListening() {
        isListening = false;
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
