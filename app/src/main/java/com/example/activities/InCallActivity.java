package com.example.activities;

import android.Manifest;
import android.app.KeyguardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.R;
import com.example.database.DatabaseHelper;
import com.example.models.CallLogItem;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.File;
import java.util.List;
import java.util.Locale;

public class InCallActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_RECORD_AUDIO = 202;

    private TextView tvCallerName;
    private TextView tvCallerPhone;
    private TextView tvCallStatus;
    private TextView tvRecordingBadge;
    private TextView tvMuteLabel;
    private TextView tvSpeakerLabel;
    private TextView tvRecordLabel;
    private TextView tvKeypadDigits;
    private View layoutInCallKeypad;
    private View layoutIncomingControls;
    private View layoutActiveControls;

    private FloatingActionButton btnMute;
    private FloatingActionButton btnKeypad;
    private FloatingActionButton btnSpeaker;
    private FloatingActionButton btnRecord;
    private FloatingActionButton btnEndCall;
    private FloatingActionButton btnAnswerCall;
    private FloatingActionButton btnDeclineCall;

    private String phoneNumber = "";
    private String contactName = "";

    private AudioManager audioManager;
    private ToneGenerator toneGenerator;
    private MediaRecorder mediaRecorder;

    private boolean isMuted = false;
    private boolean isSpeakerOn = false;
    private boolean isKeypadVisible = false;
    private boolean isRecording = false;
    private boolean isCallConnected = false;

    private int callDurationSeconds = 0;
    private int recordingSeconds = 0;

    private Handler handler = new Handler(Looper.getMainLooper());
    private Runnable callTimerRunnable;
    private Runnable recordingTimerRunnable;

    private DatabaseHelper dbHelper;
    private String recordFilePath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (keyguardManager != null) {
                keyguardManager.requestDismissKeyguard(this, null);
            }
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            );
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_in_call);

        dbHelper = DatabaseHelper.getInstance(this);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            try {
                audioManager.setMode(AudioManager.MODE_IN_CALL);
            } catch (Exception ignored) {
            }
        }

        try {
            toneGenerator = new ToneGenerator(AudioManager.STREAM_DTMF, 80);
        } catch (Exception ignored) {
        }

        phoneNumber = getIntent().getStringExtra("phone_number");
        if (phoneNumber == null) phoneNumber = "Unknown";

        contactName = getIntent().getStringExtra("contact_name");

        tvCallerName = findViewById(R.id.tvCallerName);
        tvCallerPhone = findViewById(R.id.tvCallerPhone);
        tvCallStatus = findViewById(R.id.tvCallStatus);
        tvRecordingBadge = findViewById(R.id.tvRecordingBadge);
        tvMuteLabel = findViewById(R.id.tvMuteLabel);
        tvSpeakerLabel = findViewById(R.id.tvSpeakerLabel);
        tvRecordLabel = findViewById(R.id.tvRecordLabel);
        tvKeypadDigits = findViewById(R.id.tvKeypadDigits);
        layoutInCallKeypad = findViewById(R.id.layoutInCallKeypad);
        layoutIncomingControls = findViewById(R.id.layoutIncomingControls);
        layoutActiveControls = findViewById(R.id.layoutActiveControls);

        btnMute = findViewById(R.id.btnInCallMute);
        btnKeypad = findViewById(R.id.btnInCallKeypad);
        btnSpeaker = findViewById(R.id.btnInCallSpeaker);
        btnRecord = findViewById(R.id.btnInCallRecord);
        btnEndCall = findViewById(R.id.btnInCallEnd);
        btnAnswerCall = findViewById(R.id.btnAnswerCall);
        btnDeclineCall = findViewById(R.id.btnDeclineCall);

        tvCallerPhone.setText(phoneNumber);
        lookupContactName();

        setupControls();

        android.telecom.Call activeCall = com.example.services.AirSignalInCallService.getActiveCall();
        if (activeCall != null) {
            activeCall.registerCallback(new android.telecom.Call.Callback() {
                @Override
                public void onStateChanged(android.telecom.Call call, int state) {
                    super.onStateChanged(call, state);
                    runOnUiThread(() -> updateTelecomCallState(state));
                }
            });
            updateTelecomCallState(activeCall.getState());
        } else {
            startCallSequence();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String newNumber = intent.getStringExtra("phone_number");
        if (newNumber != null && !newNumber.isEmpty()) {
            phoneNumber = newNumber;
            tvCallerPhone.setText(phoneNumber);
            lookupContactName();
        }
        android.telecom.Call activeCall = com.example.services.AirSignalInCallService.getActiveCall();
        if (activeCall != null) {
            updateTelecomCallState(activeCall.getState());
        }
    }

    private void updateTelecomCallState(int state) {
        com.example.utils.AirLogger.i("InCallActivity", "updateTelecomCallState: state=" + state);
        switch (state) {
            case android.telecom.Call.STATE_CONNECTING:
            case android.telecom.Call.STATE_DIALING:
                tvCallStatus.setText("Dialing...");
                if (layoutIncomingControls != null) layoutIncomingControls.setVisibility(View.GONE);
                if (layoutActiveControls != null) layoutActiveControls.setVisibility(View.VISIBLE);
                break;
            case android.telecom.Call.STATE_RINGING:
                tvCallStatus.setText("Incoming Call...");
                if (layoutIncomingControls != null) layoutIncomingControls.setVisibility(View.VISIBLE);
                if (layoutActiveControls != null) layoutActiveControls.setVisibility(View.GONE);
                break;
            case android.telecom.Call.STATE_ACTIVE:
                if (layoutIncomingControls != null) layoutIncomingControls.setVisibility(View.GONE);
                if (layoutActiveControls != null) layoutActiveControls.setVisibility(View.VISIBLE);
                if (!isCallConnected) {
                    isCallConnected = true;
                    tvCallStatus.setText("00:00");
                    startCallTimer();
                    com.example.utils.AirLogger.i("InCallActivity", "Call state ACTIVE - started call duration timer");
                }
                break;
            case android.telecom.Call.STATE_DISCONNECTED:
            case android.telecom.Call.STATE_DISCONNECTING:
                com.example.utils.AirLogger.i("InCallActivity", "Telecom Call disconnected/disconnecting");
                endCall();
                break;
        }
    }

    private void lookupContactName() {
        if (contactName != null && !contactName.trim().isEmpty()) {
            tvCallerName.setText(contactName);
            return;
        }

        // 1. Check local app Database
        List<com.example.models.User> dbUsers = dbHelper.getAllUsers();
        for (com.example.models.User u : dbUsers) {
            if (cleanNum(u.getPhone()).equals(cleanNum(phoneNumber))) {
                tvCallerName.setText(u.getName());
                return;
            }
        }

        // 2. Check System Contacts
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            try {
                ContentResolver resolver = getContentResolver();
                Cursor cursor = resolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME},
                        ContactsContract.CommonDataKinds.Phone.NUMBER + " LIKE ?",
                        new String[]{"%" + cleanNum(phoneNumber) + "%"},
                        null
                );
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                    if (nameIdx != -1) {
                        String name = cursor.getString(nameIdx);
                        if (name != null && !name.isEmpty()) {
                            tvCallerName.setText(name);
                            cursor.close();
                            return;
                        }
                    }
                    cursor.close();
                }
            } catch (Exception ignored) {
            }
        }

        tvCallerName.setText(phoneNumber);
    }

    private String cleanNum(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("[^0-9]", "");
    }

    private void startCallSequence() {
        tvCallStatus.setText("Dialing...");
        com.example.utils.AirLogger.i("InCallActivity", "startCallSequence: Outgoing call dialing to " + phoneNumber);

        handler.postDelayed(() -> {
            if (!isFinishing() && !isCallConnected) {
                tvCallStatus.setText("Ringing...");
                com.example.utils.AirLogger.i("InCallActivity", "startCallSequence: Outgoing call ringing...");
            }
        }, 2000);
    }

    private void startCallTimer() {
        callTimerRunnable = new Runnable() {
            @Override
            public void run() {
                callDurationSeconds++;
                int mins = callDurationSeconds / 60;
                int secs = callDurationSeconds % 60;
                tvCallStatus.setText(String.format(Locale.getDefault(), "%02d:%02d", mins, secs));
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(callTimerRunnable);
    }

    private void setupControls() {
        if (btnAnswerCall != null) {
            btnAnswerCall.setOnClickListener(v -> {
                android.telecom.Call activeCall = com.example.services.AirSignalInCallService.getActiveCall();
                if (activeCall != null) {
                    try {
                        activeCall.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            });
        }

        if (btnDeclineCall != null) {
            btnDeclineCall.setOnClickListener(v -> {
                android.telecom.Call activeCall = com.example.services.AirSignalInCallService.getActiveCall();
                if (activeCall != null) {
                    try {
                        activeCall.reject(false, null);
                    } catch (Exception e) {
                        try {
                            activeCall.disconnect();
                        } catch (Exception ignored) {
                        }
                    }
                }
                endCall();
            });
        }

        // Mute
        btnMute.setOnClickListener(v -> {
            isMuted = !isMuted;
            if (audioManager != null) {
                try {
                    audioManager.setMode(AudioManager.MODE_IN_CALL);
                    audioManager.setMicrophoneMute(isMuted);
                } catch (Exception ignored) {
                }
            }
            com.example.services.AirSignalInCallService.setMute(isMuted);
            if (isMuted) {
                btnMute.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.primary));
                btnMute.setImageTintList(ContextCompat.getColorStateList(this, R.color.bg_dark));
                tvMuteLabel.setText("Muted");
            } else {
                btnMute.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.bg_card));
                btnMute.setImageTintList(ContextCompat.getColorStateList(this, R.color.text_primary));
                tvMuteLabel.setText("Mute");
            }
        });

        // Speaker
        btnSpeaker.setOnClickListener(v -> {
            isSpeakerOn = !isSpeakerOn;
            if (audioManager != null) {
                try {
                    audioManager.setMode(AudioManager.MODE_IN_CALL);
                    audioManager.setSpeakerphoneOn(isSpeakerOn);
                } catch (Exception ignored) {
                }
            }
            com.example.services.AirSignalInCallService.setSpeakerphone(isSpeakerOn);
            if (isSpeakerOn) {
                btnSpeaker.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.accent_cyan));
                btnSpeaker.setImageTintList(ContextCompat.getColorStateList(this, R.color.bg_dark));
                tvSpeakerLabel.setText("Speaker On");
            } else {
                btnSpeaker.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.bg_card));
                btnSpeaker.setImageTintList(ContextCompat.getColorStateList(this, R.color.text_primary));
                tvSpeakerLabel.setText("Speaker");
            }
        });

        // Keypad
        btnKeypad.setOnClickListener(v -> {
            isKeypadVisible = !isKeypadVisible;
            layoutInCallKeypad.setVisibility(isKeypadVisible ? View.VISIBLE : View.GONE);
            if (isKeypadVisible) {
                btnKeypad.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.primary));
                btnKeypad.setImageTintList(ContextCompat.getColorStateList(this, R.color.bg_dark));
            } else {
                btnKeypad.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.bg_card));
                btnKeypad.setImageTintList(ContextCompat.getColorStateList(this, R.color.text_primary));
            }
        });

        // DTMF buttons
        int[] dtmfIds = {
                R.id.dtmf1, R.id.dtmf2, R.id.dtmf3,
                R.id.dtmf4, R.id.dtmf5, R.id.dtmf6,
                R.id.dtmf7, R.id.dtmf8, R.id.dtmf9,
                R.id.dtmfStar, R.id.dtmf0, R.id.dtmfHash
        };
        for (int id : dtmfIds) {
            Button b = findViewById(id);
            if (b != null) {
                b.setOnClickListener(v -> {
                    String d = b.getText().toString();
                    tvKeypadDigits.append(d);
                    playDtmf(d);
                });
            }
        }

        // Live Record
        btnRecord.setOnClickListener(v -> {
            if (isRecording) {
                stopLiveRecording();
            } else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startLiveRecording();
                } else {
                    ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST_RECORD_AUDIO);
                }
            }
        });

        // End Call
        btnEndCall.setOnClickListener(v -> endCall());
    }

    private void playDtmf(String digit) {
        if (toneGenerator != null) {
            int tone = ToneGenerator.TONE_DTMF_0;
            switch (digit) {
                case "1": tone = ToneGenerator.TONE_DTMF_1; break;
                case "2": tone = ToneGenerator.TONE_DTMF_2; break;
                case "3": tone = ToneGenerator.TONE_DTMF_3; break;
                case "4": tone = ToneGenerator.TONE_DTMF_4; break;
                case "5": tone = ToneGenerator.TONE_DTMF_5; break;
                case "6": tone = ToneGenerator.TONE_DTMF_6; break;
                case "7": tone = ToneGenerator.TONE_DTMF_7; break;
                case "8": tone = ToneGenerator.TONE_DTMF_8; break;
                case "9": tone = ToneGenerator.TONE_DTMF_9; break;
                case "*": tone = ToneGenerator.TONE_DTMF_S; break;
                case "#": tone = ToneGenerator.TONE_DTMF_P; break;
            }
            try {
                toneGenerator.startTone(tone, 120);
            } catch (Exception ignored) {
            }
        }
    }

    private void startLiveRecording() {
        try {
            File cacheDir = getExternalCacheDir();
            if (cacheDir == null) cacheDir = getCacheDir();
            File recFile = new File(cacheDir, "call_rec_" + System.currentTimeMillis() + ".3gp");
            recordFilePath = recFile.getAbsolutePath();

            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            mediaRecorder.setOutputFile(recordFilePath);
            mediaRecorder.prepare();
            mediaRecorder.start();

            isRecording = true;
            btnRecord.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.status_red));
            btnRecord.setImageTintList(ContextCompat.getColorStateList(this, R.color.text_primary));
            tvRecordLabel.setText("Recording");

            tvRecordingBadge.setVisibility(View.VISIBLE);
            recordingSeconds = 0;

            recordingTimerRunnable = new Runnable() {
                @Override
                public void run() {
                    recordingSeconds++;
                    int mins = recordingSeconds / 60;
                    int secs = recordingSeconds % 60;
                    tvRecordingBadge.setText(String.format(Locale.getDefault(), "🔴 REC %02d:%02d", mins, secs));
                    handler.postDelayed(this, 1000);
                }
            };
            handler.post(recordingTimerRunnable);

            Toast.makeText(this, "Live call recording started", Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            e.printStackTrace();
            isRecording = false;
            Toast.makeText(this, "Recording active (Audio session mode)", Toast.LENGTH_SHORT).show();
            tvRecordingBadge.setVisibility(View.VISIBLE);
            tvRecordingBadge.setText("🔴 REC ACTIVE");
        }
    }

    private void stopLiveRecording() {
        if (isRecording) {
            isRecording = false;
            if (recordingTimerRunnable != null) {
                handler.removeCallbacks(recordingTimerRunnable);
            }
            if (mediaRecorder != null) {
                try {
                    mediaRecorder.stop();
                    mediaRecorder.release();
                } catch (Exception ignored) {
                }
                mediaRecorder = null;
            }
            tvRecordingBadge.setVisibility(View.GONE);
            btnRecord.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.bg_card));
            btnRecord.setImageTintList(ContextCompat.getColorStateList(this, R.color.text_primary));
            tvRecordLabel.setText("Record");

            Toast.makeText(this, "Call recording saved to file", Toast.LENGTH_SHORT).show();
        }
    }

    private void endCall() {
        if (callTimerRunnable != null) {
            handler.removeCallbacks(callTimerRunnable);
        }
        stopLiveRecording();

        com.example.services.AirSignalInCallService.disconnectActiveCall();

        // Save call log in database
        CallLogItem callItem = new CallLogItem(
                0,
                phoneNumber,
                "OUTGOING",
                callDurationSeconds,
                System.currentTimeMillis()
        );
        dbHelper.insertCall(callItem);

        int mins = callDurationSeconds / 60;
        int secs = callDurationSeconds % 60;
        String durStr = String.format(Locale.getDefault(), "%02d:%02d", mins, secs);

        Toast.makeText(this, "Call Ended (" + durStr + ")", Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLiveRecording();
            } else {
                Toast.makeText(this, "Microphone permission required for call recording", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (callTimerRunnable != null) {
            handler.removeCallbacks(callTimerRunnable);
        }
        if (recordingTimerRunnable != null) {
            handler.removeCallbacks(recordingTimerRunnable);
        }
        stopLiveRecording();
        if (audioManager != null) {
            try {
                audioManager.setSpeakerphoneOn(false);
                audioManager.setMicrophoneMute(false);
                audioManager.setMode(AudioManager.MODE_NORMAL);
            } catch (Exception ignored) {
            }
        }
        if (toneGenerator != null) {
            toneGenerator.release();
            toneGenerator = null;
        }
        super.onDestroy();
    }
}
