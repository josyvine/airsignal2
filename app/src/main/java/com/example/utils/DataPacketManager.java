package com.example.utils;

import android.util.Base64;
import com.example.models.DataPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class DataPacketManager {

    private static final int MAX_SMS_PAYLOAD_SIZE = 120; // safe chunk size inside 160 char SMS

    public static List<DataPacket> createPackets(byte[] binaryData) {
        List<DataPacket> packets = new ArrayList<>();
        String fileId = UUID.randomUUID().toString().substring(0, 8);

        String base64Data = Base64.encodeToString(binaryData, Base64.NO_WRAP);
        int totalLength = base64Data.length();
        int totalPackets = (int) Math.ceil((double) totalLength / MAX_SMS_PAYLOAD_SIZE);

        for (int i = 0; i < totalPackets; i++) {
            int start = i * MAX_SMS_PAYLOAD_SIZE;
            int end = Math.min(start + MAX_SMS_PAYLOAD_SIZE, totalLength);
            String chunk = base64Data.substring(start, end);

            long crc = EncryptionUtils.calculateCRC32(chunk.getBytes());
            packets.add(new DataPacket(fileId, i + 1, totalPackets, chunk, crc));
        }

        return packets;
    }

    public static String formatSmsPacket(DataPacket packet) {
        return String.format("AIR_START|ID:%s|PART:%03d/%03d|DATA:%s|CRC:%d",
                packet.getFileId(),
                packet.getPacketIndex(),
                packet.getTotalPackets(),
                packet.getPayload(),
                packet.getChecksum());
    }

    public static DataPacket parseSmsPacket(String rawSms) {
        if (rawSms == null || !rawSms.startsWith("AIR_START|")) {
            return null;
        }

        try {
            String[] parts = rawSms.split("\\|");
            String fileId = parts[1].replace("ID:", "");
            String[] partInfo = parts[2].replace("PART:", "").split("/");
            int index = Integer.parseInt(partInfo[0]);
            int total = Integer.parseInt(partInfo[1]);
            String payload = parts[3].replace("DATA:", "");
            long crc = Long.parseLong(parts[4].replace("CRC:", ""));

            return new DataPacket(fileId, index, total, payload, crc);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
