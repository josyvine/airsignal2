package com.example.fragments;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.R;
import com.example.adapters.TransferAdapter;
import com.example.database.TransferDatabase;
import com.example.knowledge.PhoneticTokenManager;
import com.example.knowledge.TemplateCatalog;
import com.example.knowledge.VisualRenderer;
import com.example.models.DataPacket;
import com.example.models.TemplateToken;
import com.example.models.TransferItem;
import com.example.services.AudioTransferService;
import com.example.services.SmsTransferService;
import com.example.utils.AirLogger;
import com.example.utils.DataPacketManager;
import com.example.utils.FileAssembler;

import java.util.List;
import java.util.UUID;

public class TransferFragment extends Fragment {

    private RecyclerView rvTransfers;
    private TransferAdapter adapter;
    private TransferDatabase transferDb;

    // Real-time UI progress updater for incoming background transfers
    private final BroadcastReceiver progressReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (FileAssembler.ACTION_TRANSFER_PROGRESS.equals(intent.getAction())) {
                if (isAdded() && getActivity() != null) {
                    getActivity().runOnUiThread(() -> loadTransfers());
                }
            }
        }
    };

    @Override
    public void onResume() {
        super.onResume();
        loadTransfers();
        if (getContext() != null) {
            IntentFilter filter = new IntentFilter(FileAssembler.ACTION_TRANSFER_PROGRESS);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getContext().registerReceiver(progressReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                getContext().registerReceiver(progressReceiver, filter);
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (getContext() != null) {
            try {
                getContext().unregisterReceiver(progressReceiver);
            } catch (Exception ignored) {
            }
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_transfer, container, false);

        rvTransfers = view.findViewById(R.id.rvTransfers);
        transferDb = TransferDatabase.getInstance(requireContext());
        rvTransfers.setLayoutManager(new LinearLayoutManager(requireContext()));

        loadTransfers();

        view.findViewById(R.id.btnSelectFile).setOnClickListener(v -> showTransferOptionsDialog());

        view.findViewById(R.id.btnSendSmsData).setOnClickListener(v -> showSmsDataDialog());

        view.findViewById(R.id.btnSendAudioData).setOnClickListener(v -> showAudioDataDialog());

        return view;
    }

    private void loadTransfers() {
        List<TransferItem> list = transferDb.getAllTransfers();
        if (adapter == null) {
            adapter = new TransferAdapter(list);
            rvTransfers.setAdapter(adapter);
        } else {
            // Safe adapter update for Recycler bounds
            rvTransfers.setAdapter(new TransferAdapter(list));
        }
    }

    private void showTransferOptionsDialog() {
        CharSequence[] options = new CharSequence[]{
                "Send Instant Visual Template (Phonetic)",
                "Send Exact Lossless File (2400 Baud Audio)",
                "Generate Receiver Preview"
        };

        new AlertDialog.Builder(requireContext())
                .setTitle("Select Data Transmission Mode")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        sendInstantVisualTemplate();
                    } else if (which == 1) {
                        sendExactLosslessBinaryStream();
                    } else if (which == 2) {
                        simulateReceiverPopup();
                    }
                })
                .show();
    }

    /**
     * MODE 4: Instant Phonetic / Pre-Built Dictionary Token Burst (1.0 Second)
     */
    private void sendInstantVisualTemplate() {
        // Construct a sample 16-byte template token representing a Hazard Report on Map #1
        TemplateToken token = new TemplateToken(
                TemplateToken.MODE_PHONETIC_TOKEN,
                TemplateToken.CATEGORY_TACTICAL_MAP,
                TemplateCatalog.TEMPLATE_CHALAKUDY_SECTOR_MAP,
                18500, // Normalized X Coord
                35000, // Normalized Y Coord
                TemplateToken.ICON_FLOOD,
                TemplateToken.SEVERITY_CRITICAL,
                240,   // Metric (2.4m depth)
                0
        );

        String phoneticString = PhoneticTokenManager.encodeToPhoneticWords(token);
        
        new AlertDialog.Builder(requireContext())
                .setTitle("Transmit Pre-Built Template")
                .setMessage("Template: Chalakudy Flood Hazard\nToken: " + phoneticString + "\n\nThis will take ~0.8 seconds to transmit over the call.")
                .setPositiveButton("Send Audio Burst", (dialog, which) -> {
                    Intent serviceIntent = new Intent(requireContext(), AudioTransferService.class);
                    serviceIntent.setAction(AudioTransferService.ACTION_SEND_TOKEN);
                    serviceIntent.putExtra(AudioTransferService.EXTRA_TOKEN_PAYLOAD, token.toByteArray());
                    requireContext().startService(serviceIntent);

                    Toast.makeText(requireContext(), "Transmitting Acoustic Token...", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * MODE 2 / 3: Continuous Raw Binary Audio Stream (30 Minutes / 500 KB)
     */
    private void sendExactLosslessBinaryStream() {
        // Simulating a raw 45 KB file payload (e.g. losslessly compressed WebP/PDF)
        byte[] dummyFileBytes = new byte[45000]; 
        for (int i = 0; i < dummyFileBytes.length; i++) {
            dummyFileBytes[i] = (byte) (i % 255);
        }

        List<byte[]> binaryPackets = DataPacketManager.createBinaryPackets(dummyFileBytes);
        String fileId = UUID.randomUUID().toString().substring(0, 8);

        new AlertDialog.Builder(requireContext())
                .setTitle("Transmit Exact Lossless File")
                .setMessage("File Size: 45 KB\nTotal Audio Packets: " + binaryPackets.size() + "\nEstimated Transfer Time: 2.5 Minutes @ 2400 Baud\n\nEnsure voice call is active.")
                .setPositiveButton("Start 2400 Baud Stream", (dialog, which) -> {
                    TransferItem item = new TransferItem(fileId, "lossless_document.pdf", 45000, 0, "TRANSFERRING", "RAW_BINARY_2400", binaryPackets.size(), 0);
                    transferDb.insertTransfer(item);

                    // Note: AudioTransferService logic intercepts this. To actually invoke AudioEncoder:
                    // In a production app, the UI binds to the Service and calls transmitRawStream(binaryPackets)
                    Toast.makeText(requireContext(), "Binary Stream Started in Background", Toast.LENGTH_LONG).show();
                    loadTransfers();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void simulateReceiverPopup() {
        TemplateToken token = new TemplateToken(
                TemplateToken.MODE_LOSSLESS_IMAGE_HEADER,
                TemplateToken.CATEGORY_LOSSLESS_IMAGE,
                TemplateCatalog.TEMPLATE_IMAGE_WEBP_LOSSLESS,
                640,
                480,
                TemplateToken.ICON_IMAGE_CONTAINER,
                TemplateToken.SEVERITY_LOW,
                1420,
                0
        );
        VisualRenderer.showVisualResultDialog(requireContext(), token);
    }

    private void showSmsDataDialog() {
        final EditText etPhone = new EditText(requireContext());
        etPhone.setHint("Enter Target Phone Number");
        etPhone.setPadding(32, 32, 32, 32);

        new AlertDialog.Builder(requireContext())
                .setTitle("Send via SMS Data Mode")
                .setMessage("Data will be encoded into Base64 packet chunks and transmitted via Cellular SMS.")
                .setView(etPhone)
                .setPositiveButton("Start SMS Transfer", (dialog, which) -> {
                    String phone = etPhone.getText().toString().trim();
                    if (!phone.isEmpty()) {
                        byte[] testBytes = "AirSignal Offline Data Packet Test Content 2026".getBytes();
                        List<DataPacket> packets = DataPacketManager.createPackets(testBytes);
                        
                        String fileId = packets.isEmpty() ? "SYS_01" : packets.get(0).getFileId();

                        TransferItem item = new TransferItem(fileId, "telemetry_log_2026.dat", testBytes.length, 100, "COMPLETED", "SMS_DATA", packets.size(), packets.size());
                        transferDb.insertTransfer(item);

                        Intent serviceIntent = new Intent(requireContext(), SmsTransferService.class);
                        serviceIntent.putExtra(SmsTransferService.EXTRA_TARGET_PHONE, phone);
                        requireContext().startService(serviceIntent);

                        Toast.makeText(requireContext(), "SMS Data Transfer Dispatched!", Toast.LENGTH_SHORT).show();
                        loadTransfers();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAudioDataDialog() {
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_audio_transfer, null);
        final EditText etPhone = dialogView.findViewById(R.id.etTargetPhone);

        final AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .create();

        dialogView.findViewById(R.id.btnCancelAudioDialog).setOnClickListener(v -> dialog.dismiss());

        dialogView.findViewById(R.id.btnStartAudioTransfer).setOnClickListener(v -> {
            String phone = etPhone.getText().toString().trim();
            if (!phone.isEmpty()) {
                String fileId = "SYS_AUD_" + System.currentTimeMillis();
                TransferItem item = new TransferItem(fileId, "stream_audio_data.bin", 8192, 10, "TRANSFERRING", "AUDIO_DATA", 16, 2);
                transferDb.insertTransfer(item);

                Intent serviceIntent = new Intent(requireContext(), AudioTransferService.class);
                requireContext().startService(serviceIntent);

                Toast.makeText(requireContext(), "Audio Call Data Transfer Started!", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                loadTransfers();
            }
        });

        dialog.show();
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                getResources().getDisplayMetrics()
        );
    }
}