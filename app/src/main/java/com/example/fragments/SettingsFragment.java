package com.example.fragments;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.R;
import com.example.utils.AirLogger;
import com.example.utils.SmsRoleManager;
import com.google.android.material.slider.Slider;

public class SettingsFragment extends Fragment {

    private TextView tvDefaultSmsStatus;
    private TextView tvDefaultDialerStatus;
    private TextView tvBaudRateVal;
    private Slider sliderBaudRate;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);

        tvDefaultSmsStatus = view.findViewById(R.id.tvDefaultSmsStatus);
        tvDefaultDialerStatus = view.findViewById(R.id.tvDefaultDialerStatus);
        tvBaudRateVal = view.findViewById(R.id.tvBaudRateVal);
        sliderBaudRate = view.findViewById(R.id.sliderBaudRate);

        updateRoleStatuses();

        view.findViewById(R.id.btnSetDefaultSms).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SmsRoleManager.requestDefaultSmsRole(requireActivity());
            }
        });

        view.findViewById(R.id.btnSetDefaultDialer).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SmsRoleManager.requestDefaultDialerRole(requireActivity());
            }
        });

        view.findViewById(R.id.btnOpenSystemDefaultApps).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SmsRoleManager.openSystemDefaultAppsSettings(requireContext());
            }
        });

        sliderBaudRate.addOnChangeListener(new Slider.OnChangeListener() {
            @Override
            public void onValueChange(@NonNull Slider slider, float value, boolean fromUser) {
                int baud = (int) value;
                tvBaudRateVal.setText(baud + " Baud (FSK Tones)");
            }
        });

        view.findViewById(R.id.btnViewAirLogs).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showLogViewerDialog();
            }
        });

        view.findViewById(R.id.btnClearAirLogs).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                AirLogger.clearLogs();
                Toast.makeText(requireContext(), "AirLog file cleared", Toast.LENGTH_SHORT).show();
            }
        });

        return view;
    }

    private void showLogViewerDialog() {
        String logContent = AirLogger.readLogContent();
        if (logContent.isEmpty()) {
            logContent = "No log entries found yet in Download/airlog/air_actions.log";
        }

        final String finalLogContent = logContent;

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle("AirSignal Action Log")
                .setMessage(finalLogContent)
                .setPositiveButton("OK", null)
                .setNegativeButton("Copy Log", (d, which) -> {
                    try {
                        ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                        ClipData clip = ClipData.newPlainText("AirSignal Action Log", finalLogContent);
                        if (clipboard != null) {
                            clipboard.setPrimaryClip(clip);
                            Toast.makeText(requireContext(), "Action log copied to clipboard!", Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        Toast.makeText(requireContext(), "Failed to copy log: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNeutralButton("Clear", (d, which) -> {
                    AirLogger.clearLogs();
                    Toast.makeText(requireContext(), "Logs cleared", Toast.LENGTH_SHORT).show();
                })
                .create();

        dialog.show();

        // Attach copy_all_24px icon directly to the Copy Log button in the dialog
        try {
            Button copyButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (copyButton != null) {
                int copyIconId = getResources().getIdentifier("copy_all_24px", "drawable", requireContext().getPackageName());
                if (copyIconId != 0) {
                    Drawable icon = ContextCompat.getDrawable(requireContext(), copyIconId);
                    if (icon != null) {
                        copyButton.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null);
                        copyButton.setCompoundDrawablePadding(dpToPx(6));
                    }
                }
            }
        } catch (Exception e) {
            AirLogger.e("SettingsFragment", "Failed to set copy icon on dialog button", e);
        }
    }

    private void updateRoleStatuses() {
        boolean smsDefault = SmsRoleManager.isDefaultSmsApp(requireContext());
        boolean dialerDefault = SmsRoleManager.isDefaultDialerApp(requireContext());

        tvDefaultSmsStatus.setText(smsDefault ? "Status: Default SMS Handler Active" : "Status: Not Default SMS App");
        tvDefaultDialerStatus.setText(dialerDefault ? "Status: Default Phone Handler Active" : "Status: Not Default Phone App");
    }

    @Override
    public void onResume() {
        super.onResume();
        updateRoleStatuses();
    }

    private int dpToPx(int dp) {
        if (getContext() == null) return dp;
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                getResources().getDisplayMetrics()
        );
    }
}