package com.example.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.example.audio.AudioEncoder;
import com.example.audio.AudioReceiver;

public class AudioTransferService extends Service implements AudioReceiver.AudioDecoderListener {

    public static final String CHANNEL_ID = "audio_transfer_channel";

    private AudioReceiver audioReceiver;
    private AudioEncoder audioEncoder;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        audioEncoder = new AudioEncoder(1200);
        audioReceiver = new AudioReceiver(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("AirSignal Audio Data Mode Active")
                .setContentText("Listening and modulating data over voice call stream")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build();

        startForeground(202, notification);

        // Turn on speakerphone for call stream listening/playback if needed
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            audioManager.setMode(AudioManager.MODE_IN_CALL);
            audioManager.setSpeakerphoneOn(true);
        }

        audioReceiver.startListening();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (audioReceiver != null) {
            audioReceiver.stopListening();
        }
        super.onDestroy();
    }

    @Override
    public void onByteDecoded(byte b) {
        // Acoustic byte received
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Audio Data Call Service",
                    NotificationManager.IMPORTANCE_HIGH
            );
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}
