package com.example.utils;

import android.content.Context;
import android.util.Base64;
import com.example.models.DataPacket;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class FileAssembler {

    public static File assembleFile(Context context, String filename, List<DataPacket> packets) {
        try {
            // Sort packets by index
            Collections.sort(packets, new Comparator<DataPacket>() {
                @Override
                public int compare(DataPacket p1, DataPacket p2) {
                    return Integer.compare(p1.getPacketIndex(), p2.getPacketIndex());
                }
            });

            StringBuilder sb = new StringBuilder();
            for (DataPacket packet : packets) {
                sb.append(packet.getPayload());
            }

            byte[] decodedBytes = Base64.decode(sb.toString(), Base64.NO_WRAP);

            File outputDir = context.getExternalFilesDir(null);
            if (outputDir == null) {
                outputDir = context.getFilesDir();
            }

            File outFile = new File(outputDir, filename);
            FileOutputStream fos = new FileOutputStream(outFile);
            fos.write(decodedBytes);
            fos.flush();
            fos.close();

            return outFile;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static boolean isComplete(List<DataPacket> packets, int expectedTotal) {
        if (packets == null || packets.size() < expectedTotal) {
            return false;
        }
        return packets.size() == expectedTotal;
    }
}
