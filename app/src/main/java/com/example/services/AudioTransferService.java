package com.example.services;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.example.audio.AudioEncoder;
import com.example.audio.AudioReceiver;
import com.example.knowledge.PhoneticImageTransceiver;
import com.example.knowledge.VisualRenderer;
import com.example.models.TemplateToken;
import com.example.utils.AirLogger;
import com.example.utils.FileAssembler;

import java.io.File;
import java.util.List;

public class AudioTransferService extends Service implements AudioReceiver.AudioReceiverListener {

    private static final String TAG = "AudioTransferService";
    public static final String CHANNEL_ID = "audio_transfer_channel";
    private static final int NOTIFICATION_ID = 202;

    public static final String ACTION_SEND_TOKEN = "com.example.ACTION_SEND_TOKEN";
    public static final String ACTION_SEND_PHONETIC_IMAGE = "com.example.ACTION_SEND_PHONETIC_IMAGE";
    public static final String ACTION_SEND_RAW_BINARY = "com.example.ACTION_SEND_RAW_BINARY";
    public static final String ACTION_STOP_SERVICE = "com.example.ACTION_STOP_SERVICE";
    
    public static final String EXTRA_TOKEN_PAYLOAD = "extra_token_payload";
    public static final String EXTRA_IMAGE_PATH = "extra_image_path";
    public static final String EXTRA_BINARY_FILE_PATH = "extra_binary_file_path";

    private AudioReceiver audioReceiver;
    private AudioEncoder audioEncoder;
    private NotificationManager notificationManager;
    private NotificationCompat.Builder notificationBuilder;
    private PowerManager.WakeLock wakeLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        AirLogger.i(TAG, "Initializing AudioTransferService");

        createNotificationChannel();

        // Obtain CPU WakeLock to prevent device doze during 30-minute large file transfers
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AirSignal::AudioTransferWakeLock");
        }

        // Initialize 2400 Baud audio hardware engine
        audioEncoder = new AudioEncoder(2400);
        audioReceiver = new AudioReceiver(this);
        audioReceiver.setBaudRate(2400);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP_SERVICE.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // 1. Process Outbound Mode 4 Semantic Token Action
        if (intent != null && ACTION_SEND_TOKEN.equals(intent.getAction())) {
            byte[] tokenBytes = intent.getByteArrayExtra(EXTRA_TOKEN_PAYLOAD);
            if (tokenBytes != null) {
                TemplateToken token = TemplateToken.fromByteArray(tokenBytes);
                if (token != null) {
                    audioEncoder.transmitPhoneticToken(token, new AudioEncoder.OnTransmissionProgressListener() {
                        @Override
                        public void onProgress(int currentPacket, int totalPackets, int percent) {
                            updateNotification("Transmitting Semantic Token...", percent);
                        }

                        @Override
                        public void onComplete() {
                            updateNotification("Listening for incoming data...", 0);
                        }

                        @Override
                        public void onError(Exception e) {
                            updateNotification("Transmission Error: " + e.getMessage(), 0);
                        }
                    });
                }
            }
            return START_STICKY;
        }

        // 2. Process Outbound Phonetic Base64 Image Action
        if (intent != null && ACTION_SEND_PHONETIC_IMAGE.equals(intent.getAction())) {
            String imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH);
            if (imagePath != null) {
                File imgFile = new File(imagePath);
                if (imgFile.exists()) {
                    PhoneticImageTransceiver.sendImageViaPhoneticDictionary(
                            getApplicationContext(),
                            imgFile,
                            audioEncoder,
                            new PhoneticImageTransceiver.OnPhoneticTransferListener() {
                                @Override
                                public void onProgress(int step, int totalSteps, String statusMessage) {
                                    updateNotification("Phonetic Image: " + statusMessage, (step * 25));
                                }

                                @Override
                                public void onSuccess(int totalTokensSent, int originalBase64Length) {
                                    updateNotification("Image Sent! (" + totalTokensSent + " tokens)", 100);
                                    new android.os.Handler(getMainLooper()).postDelayed(() -> {
                                        updateNotification("Listening for incoming data...", 0);
                                    }, 3000);
                                }

                                @Override
                                public void onError(Exception e) {
                                    updateNotification("Image Send Failed: " + e.getMessage(), 0);
                                }
                            }
                    );
                }
            }
            return START_STICKY;
        }

        if (wakeLock != null && !wakeLock.isHeld()) {
            wakeLock.acquire(60 * 60 * 1000L /* 1 hour max timeout */);
        }

        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("AirSignal Audio Data Mode Active")
                .setContentText("Listening and modulating data over voice call stream")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true);

        startForeground(NOTIFICATION_ID, notificationBuilder.build());

        // Prepare audio environment for FSK acoustic tone capture
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            if (audioManager.getMode() != AudioManager.MODE_IN_CALL && audioManager.getMode() != AudioManager.MODE_IN_COMMUNICATION) {
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
                audioManager.setSpeakerphoneOn(true);
            }
        }

        // Verify runtime RECORD_AUDIO permission before engaging AudioReceiver
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            audioReceiver.startListening();
        } else {
            AirLogger.w(TAG, "RECORD_AUDIO permission not yet granted. AudioReceiver standby.");
            updateNotification("Awaiting Microphone Permission...", 0);
        }

        return START_STICKY;
    }

    private void updateNotification(String text, int progress) {
        if (notificationBuilder != null && notificationManager != null) {
            notificationBuilder.setContentText(text);
            if (progress > 0 && progress <= 100) {
                notificationBuilder.setProgress(100, progress, false);
            } else {
                notificationBuilder.setProgress(0, 0, false);
            }
            notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
        }
    }

    @Override
    public void onDestroy() {
        AirLogger.i(TAG, "Destroying AudioTransferService");

        if (audioReceiver != null) {
            audioReceiver.stopListening();
        }

        if (audioEncoder != null) {
            audioEncoder.cancelTransmission();
        }

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }

        super.onDestroy();
    }

    // =========================================================================
    // AudioReceiver Callback Handlers (Zero-Touch Automation)
    // =========================================================================

    @Override
    public void onByteDecoded(byte b) {
        // Individual raw byte logged if debugging is needed
    }

    @Override
    public void onFrameDecoded(byte[] frameData) {
        if (frameData == null || frameData.length == 0) return;

        // Check if frame contains the Phonetic Base64 Image signature
        String previewStr = new String(frameData, java.nio.charset.StandardCharsets.UTF_8);
        if (previewStr.startsWith(PhoneticImageTransceiver.PHONETIC_IMG_PREAMBLE)) {
            AirLogger.i(TAG, "Detected incoming Phonetic Base64 Image stream!");
            List<String> tokens = PhoneticImageTransceiver.parseTransmissionToTokens(frameData);
            PhoneticImageTransceiver.receiveAndReconstructImage(getApplicationContext(), tokens, "received_phonetic_photo.webp");
            
            updateNotification("Received Phonetic Image!", 100);
            new android.os.Handler(getMainLooper()).postDelayed(() -> {
                updateNotification("Listening for incoming data...", 0);
            }, 3000);
            return;
        }

        // Mode 2/3: Pass exact lossless binary frames to FileAssembler for GZIP decompression
        AirLogger.i(TAG, "Received raw binary frame (" + frameData.length + " bytes). Passing to Assembler.");
        FileAssembler.processIncomingBinaryFrame(getApplicationContext(), frameData);
    }

    @Override
    public void onTokenDecoded(TemplateToken token) {
        if (token == null) return;

        AirLogger.i(TAG, "Received valid Mode 4 Template Token! Category ID: " + token.getCategoryId());

        // Mode 4: Automatic zero-touch visual layout reconstruction popup
        VisualRenderer.showVisualResultDialog(getApplicationContext(), token);

        updateNotification("Received Emergency Visual Token!", 100);
        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            updateNotification("Listening and modulating data over voice call stream", 0);
        }, 3000);
    }

    @Override
    public void onError(Exception e) {
        AirLogger.e(TAG, "AudioReceiver encountered error", e);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "AirSignal Audio Data Channel",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Maintains CPU wake locks and streams FSK modem data during voice calls.");
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}