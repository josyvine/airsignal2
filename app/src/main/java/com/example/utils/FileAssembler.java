package com.example.utils;

import android.content.Context;
import android.content.Intent;
import android.util.Base64;
import com.example.database.TransferDatabase;
import com.example.models.DataPacket;
import com.example.models.TransferItem;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.zip.GZIPInputStream;

public class FileAssembler {

    private static final String TAG = "FileAssembler";
    public static final String ACTION_TRANSFER_PROGRESS = "com.example.ACTION_TRANSFER_PROGRESS";

    /**
     * Entry point for incoming raw binary audio frames from AudioTransferService.
     * Parses the packet, commits it to TransferDatabase, and checks if assembly is complete.
     */
    public static void processIncomingBinaryFrame(Context context, byte[] rawFrame) {
        if (context == null || rawFrame == null) return;

        DataPacket packet = DataPacketManager.parseBinaryPacket(rawFrame);
        if (packet == null) {
            AirLogger.w(TAG, "Failed to parse incoming acoustic binary frame.");
            return;
        }

        TransferDatabase db = TransferDatabase.getInstance(context);

        // Ensure transfer metadata exists in tracking table
        TransferItem transfer = db.getTransfer(packet.getFileId());
        if (transfer == null) {
            String filename = "audio_rx_" + System.currentTimeMillis() + ".bin";
            transfer = new TransferItem(
                    packet.getFileId(),
                    filename,
                    0,
                    0,
                    "RECEIVING",
                    "AUDIO_DATA",
                    packet.getTotalPackets(),
                    0
            );
            db.insertTransfer(transfer);
        }

        // Insert packet and verify completion
        boolean isComplete = db.insertPacketAndUpdateProgress(packet);

        // Broadcast progress to UI (TransferFragment)
        Intent progressIntent = new Intent(ACTION_TRANSFER_PROGRESS);
        progressIntent.putExtra("fileId", packet.getFileId());
        context.sendBroadcast(progressIntent);

        if (isComplete) {
            AirLogger.i(TAG, "All packets received for fileId=" + packet.getFileId() + ". Starting assembly.");
            db.updateTransferStatus(packet.getFileId(), "ASSEMBLING");

            List<DataPacket> allPackets = db.getAllPackets(packet.getFileId());
            File assembledFile = assembleFile(context, transfer.getFilename(), allPackets);

            if (assembledFile != null && assembledFile.exists()) {
                db.updateTransferStatus(packet.getFileId(), "COMPLETED");
                AirLogger.i(TAG, "File successfully assembled: " + assembledFile.getAbsolutePath());
            } else {
                db.updateTransferStatus(packet.getFileId(), "FAILED");
                AirLogger.e(TAG, "File assembly failed for fileId=" + packet.getFileId(), null);
            }
        }
    }

    /**
     * Assembles a list of database packets back into a cohesive file.
     * Supports both Mode 1 (SMS Base64 Text) and Mode 2/3 (Audio Raw Binary).
     */
    public static File assembleFile(Context context, String filename, List<DataPacket> packets) {
        try {
            // 1. Sort packets sequentially by index
            Collections.sort(packets, new Comparator<DataPacket>() {
                @Override
                public int compare(DataPacket p1, DataPacket p2) {
                    return Integer.compare(p1.getPacketIndex(), p2.getPacketIndex());
                }
            });

            // 2. Concatenate payloads
            StringBuilder sb = new StringBuilder();
            for (DataPacket packet : packets) {
                sb.append(packet.getPayload());
            }

            // 3. Decode Base64 (Used as internal storage transport for both SMS and Audio)
            byte[] decodedBytes = Base64.decode(sb.toString(), Base64.NO_WRAP);

            // 4. Automatically decompress if GZIP was applied by the sender
            byte[] decompressedBytes = decompressGzipIfNeeded(decodedBytes);

            // 5. Determine output directory
            File outputDir = context.getExternalFilesDir(null);
            if (outputDir == null) {
                outputDir = context.getFilesDir();
            }

            // 6. Write final file to internal storage
            File outFile = new File(outputDir, filename);
            FileOutputStream fos = new FileOutputStream(outFile);
            fos.write(decompressedBytes);
            fos.flush();
            fos.close();

            return outFile;
        } catch (Exception e) {
            AirLogger.e(TAG, "Error assembling file", e);
            return null;
        }
    }

    /**
     * Checks the magic number for GZIP (0x1F 0x8B). If detected, inflates the binary stream.
     * This allows 500 KB files to transfer in 8 minutes instead of 28 minutes.
     */
    private static byte[] decompressGzipIfNeeded(byte[] data) {
        if (data == null || data.length < 2) return data;

        // GZIP Magic Number: 0x1F8B
        if (data[0] == (byte) 0x1F && data[1] == (byte) 0x8B) {
            try {
                ByteArrayInputStream bais = new ByteArrayInputStream(data);
                GZIPInputStream gzis = new GZIPInputStream(bais);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();

                byte[] buffer = new byte[1024];
                int len;
                while ((len = gzis.read(buffer)) > 0) {
                    baos.write(buffer, 0, len);
                }

                gzis.close();
                bais.close();
                AirLogger.i(TAG, "GZIP stream decompressed successfully. Original size: " + data.length + ", Restored size: " + baos.size());
                return baos.toByteArray();
            } catch (Exception e) {
                AirLogger.w(TAG, "GZIP magic number detected, but decompression failed. Returning raw bytes.");
            }
        }
        return data; // Return original if not GZIP
    }

    /**
     * Validates if the local SQLite ledger has received all required chunks.
     */
    public static boolean isComplete(List<DataPacket> packets, int expectedTotal) {
        if (packets == null || packets.size() < expectedTotal) {
            return false;
        }
        return packets.size() == expectedTotal;
    }
}