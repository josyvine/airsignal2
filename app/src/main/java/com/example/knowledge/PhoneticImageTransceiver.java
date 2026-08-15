package com.example.knowledge;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import com.example.audio.AudioEncoder;
import com.example.utils.AirLogger;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PhoneticImageTransceiver {

    private static final String TAG = "PhoneticImageTransceiver";
    public static final String PHONETIC_IMG_PREAMBLE = "PHON_IMG::";

    public interface OnPhoneticTransferListener {
        void onProgress(int step, int totalSteps, String statusMessage);
        void onSuccess(int totalTokensSent, int originalBase64Length);
        void onError(Exception e);
    }

    /**
     * SENDER: Converts an image file to Base64, applies Phonetic Dictionary block substitution,
     * and transmits the compressed phonetic token stream over the active voice call.
     */
    public static void sendImageViaPhoneticDictionary(
            final Context context,
            final File imageFile,
            final AudioEncoder encoder,
            final OnPhoneticTransferListener listener) {

        if (imageFile == null || !imageFile.exists()) {
            if (listener != null) listener.onError(new IllegalArgumentException("Image file does not exist."));
            return;
        }

        if (encoder == null) {
            if (listener != null) listener.onError(new IllegalArgumentException("AudioEncoder is null."));
            return;
        }

        new Thread(() -> {
            try {
                if (listener != null) listener.onProgress(1, 4, "Reading image bytes from disk...");

                // 1. Read raw image bytes
                byte[] fileBytes = new byte[(int) imageFile.length()];
                try (FileInputStream fis = new FileInputStream(imageFile)) {
                    int read = fis.read(fileBytes);
                    if (read != fileBytes.length) {
                        throw new IllegalStateException("Incomplete image file read.");
                    }
                }

                if (listener != null) listener.onProgress(2, 4, "Encoding to Base64 stream...");

                // 2. Convert to Base64
                String rawBase64 = Base64.encodeToString(fileBytes, Base64.NO_WRAP);
                int originalLength = rawBase64.length();

                if (listener != null) listener.onProgress(3, 4, "Applying Phonetic Dictionary substitution...");

                // 3. Substitute blocks with pre-built dictionary words (ALPHA, BRAVO, CHARLIE...)
                List<String> phoneticTokens = PhoneticBase64Dictionary.encodeBase64ToPhoneticTokens(rawBase64);

                // 4. Format into transmission payload with sync preamble
                byte[] transmissionPayload = formatTokensForTransmission(phoneticTokens);

                if (listener != null) listener.onProgress(4, 4, "Modulating FSK tones over voice call...");

                AirLogger.i(TAG, "Transmitting image. Original Base64 chars: " + originalLength +
                        ", Dictionary tokens: " + phoneticTokens.size() + ", Payload size: " + transmissionPayload.length + " bytes.");

                // 5. Transmit audio tones over the call stream
                encoder.transmitDataOverAudio(transmissionPayload, new AudioEncoder.OnTransmissionProgressListener() {
                    @Override
                    public void onProgress(int currentPacket, int totalPackets, int percent) {
                        if (listener != null) {
                            listener.onProgress(4, 4, "Transmitting: " + percent + "%");
                        }
                    }

                    @Override
                    public void onComplete() {
                        if (listener != null) {
                            listener.onSuccess(phoneticTokens.size(), originalLength);
                        }
                    }

                    @Override
                    public void onError(Exception e) {
                        if (listener != null) listener.onError(e);
                    }
                });

            } catch (Exception e) {
                AirLogger.e(TAG, "Failed sending image via phonetic dictionary", e);
                if (listener != null) listener.onError(e);
            }
        }).start();
    }

    /**
     * RECEIVER: Accepts incoming phonetic tokens, expands every word back into its full Base64 block,
     * decodes the exact original binary image, saves it to storage, and triggers the UI popup.
     */
    public static void receiveAndReconstructImage(
            final Context context,
            final List<String> receivedTokens,
            final String outputFileName) {

        if (context == null || receivedTokens == null || receivedTokens.isEmpty()) {
            return;
        }

        new Thread(() -> {
            try {
                AirLogger.i(TAG, "Reconstructing image from " + receivedTokens.size() + " phonetic tokens.");

                // 1. Expand phonetic tokens back into the complete Base64 string
                String reconstructedBase64 = PhoneticBase64Dictionary.decodePhoneticTokensToBase64(receivedTokens);

                if (reconstructedBase64.isEmpty()) {
                    AirLogger.w(TAG, "Base64 expansion resulted in empty string.");
                    return;
                }

                // 2. Decode Base64 back into the exact original binary bytes
                byte[] exactImageBytes = Base64.decode(reconstructedBase64, Base64.NO_WRAP);

                // 3. Save to app storage
                File outputDir = context.getExternalFilesDir(null);
                if (outputDir == null) outputDir = context.getFilesDir();

                String finalName = (outputFileName != null && !outputFileName.isEmpty())
                        ? outputFileName
                        : "phonetic_photo_" + System.currentTimeMillis() + ".webp";

                File outputFile = new File(outputDir, finalName);
                try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                    fos.write(exactImageBytes);
                    fos.flush();
                }

                AirLogger.i(TAG, "Exact image successfully restored: " + outputFile.getAbsolutePath() +
                        " (" + exactImageBytes.length + " bytes)");

                // 4. Zero-Touch UI Display: Auto-pop up the exact picture on the receiver's screen
                new Handler(Looper.getMainLooper()).post(() -> {
                    VisualRenderer.showLosslessImageDialog(context, exactImageBytes, finalName);
                });

            } catch (Exception e) {
                AirLogger.e(TAG, "Failed reconstructing image from phonetic tokens", e);
            }
        }).start();
    }

    /**
     * Serializes a list of phonetic tokens into a delimited payload with preamble.
     */
    public static byte[] formatTokensForTransmission(List<String> tokens) {
        StringBuilder sb = new StringBuilder();
        sb.append(PHONETIC_IMG_PREAMBLE);
        for (int i = 0; i < tokens.size(); i++) {
            sb.append(tokens.get(i));
            if (i < tokens.size() - 1) {
                sb.append("|");
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Parses an incoming demodulated audio byte array back into a list of phonetic tokens.
     */
    public static List<String> parseTransmissionToTokens(byte[] rawPayload) {
        if (rawPayload == null || rawPayload.length == 0) {
            return new ArrayList<>();
        }

        String payloadStr = new String(rawPayload, StandardCharsets.UTF_8);
        if (!payloadStr.startsWith(PHONETIC_IMG_PREAMBLE)) {
            return new ArrayList<>();
        }

        String data = payloadStr.substring(PHONETIC_IMG_PREAMBLE.length());
        String[] splitTokens = data.split("\\|");
        return new ArrayList<>(Arrays.asList(splitTokens));
    }
}