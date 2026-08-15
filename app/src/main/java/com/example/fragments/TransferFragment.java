package com.example.fragments;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
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
import com.example.models.DataPacket;
import com.example.models.TransferItem;
import com.example.services.AudioTransferService;
import com.example.services.SmsTransferService;
import com.example.utils.DataPacketManager;

import java.util.List;

public class TransferFragment extends Fragment {

    private RecyclerView rvTransfers;
    private TransferAdapter adapter;
    private TransferDatabase transferDb;
    private TextView tvPacketStatus;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_transfer, container, false);

        rvTransfers = view.findViewById(R.id.rvTransfers);
        tvPacketStatus = view.findViewById(R.id.tvPacketStatus);
        transferDb = TransferDatabase.getInstance(requireContext());

        rvTransfers.setLayoutManager(new LinearLayoutManager(requireContext()));

        loadTransfers();

        view.findViewById(R.id.btnSelectFile).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(requireContext(), "File Selected: telemetry_log_2026.dat", Toast.LENGTH_SHORT).show();
            }
        });

        view.findViewById(R.id.btnSendSmsData).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSmsDataDialog();
            }
        });

        view.findViewById(R.id.btnSendAudioData).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAudioDataDialog();
            }
        });

        return view;
    }

    private void loadTransfers() {
        List<TransferItem> list = transferDb.getAllTransfers();
        adapter = new TransferAdapter(list);
        rvTransfers.setAdapter(adapter);
    }

    private void showSmsDataDialog() {
        final EditText etPhone = new EditText(requireContext());
        etPhone.setHint("Enter Target Phone Number");
        etPhone.setPadding(32, 32, 32, 32);

        new AlertDialog.Builder(requireContext())
                .setTitle("Send via SMS Data Mode")
                .setMessage("Data will be encoded into packet chunks and transmitted via SMS.")
                .setView(etPhone)
                .setPositiveButton("Start Transfer", (dialog, which) -> {
                    String phone = etPhone.getText().toString().trim();
                    if (!phone.isEmpty()) {
                        byte[] testBytes = "AirSignal Offline Data Packet Test Content 2026".getBytes();
                        List<DataPacket> packets = DataPacketManager.createPackets(testBytes);

                        TransferItem item = new TransferItem(0, "telemetry_log_2026.dat", testBytes.length, 100, "COMPLETED", "SMS_DATA", packets.size(), packets.size());
                        transferDb.insertTransfer(item);

                        Intent serviceIntent = new Intent(requireContext(), SmsTransferService.class);
                        serviceIntent.putExtra(SmsTransferService.EXTRA_TARGET_PHONE, phone);
                        requireContext().startService(serviceIntent);

                        Toast.makeText(requireContext(), "SMS Data Transfer Started!", Toast.LENGTH_SHORT).show();
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
                TransferItem item = new TransferItem(0, "stream_audio_data.bin", 8192, 10, "TRANSFERRING", "AUDIO_DATA", 16, 2);
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
}
