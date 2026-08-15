package com.example.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import java.io.ByteArrayOutputStream;

public class AudioEncoder {

    private int baudRate = 1200; // default baud rate

    public AudioEncoder(int baudRate) {
        this.baudRate = baudRate;
    }

    public void transmitDataOverAudio(byte[] data) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int bitDurationMs = 1000 / baudRate;
                    if (bitDurationMs < 1) bitDurationMs = 1;

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();

                    for (byte b : data) {
                        for (int bit = 7; bit >= 0; bit--) {
                            int bitVal = (b >> bit) & 1;
                            int freq = (bitVal == 1) ? ToneGenerator.MARK_FREQ : ToneGenerator.SPACE_FREQ;
                            short[] pcm = ToneGenerator.generateTone(freq, bitDurationMs);

                            for (short s : pcm) {
                                baos.write(s & 0x00FF);
                                baos.write((s >> 8) & 0x00FF);
                            }
                        }
                    }

                    byte[] pcmBytes = baos.toByteArray();

                    AudioTrack track = new AudioTrack.Builder()
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build())
                            .setAudioFormat(new AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(ToneGenerator.SAMPLE_RATE)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                    .build())
                            .setBufferSizeInBytes(pcmBytes.length)
                            .build();

                    track.play();
                    track.write(pcmBytes, 0, pcmBytes.length);
                    Thread.sleep(100);
                    track.stop();
                    track.release();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }
}
