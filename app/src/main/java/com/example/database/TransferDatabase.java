package com.example.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.example.models.DataPacket;
import com.example.models.TransferItem;

import java.util.ArrayList;
import java.util.List;

public class TransferDatabase extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "transfers.db";
    private static final int DATABASE_VERSION = 1;

    public static final String TABLE_TRANSFERS = "transfers";
    public static final String TABLE_PACKETS = "packets";

    public static final String KEY_ID = "id";
    public static final String KEY_FILENAME = "filename";
    public static final String KEY_SIZE = "size";
    public static final String KEY_PROGRESS = "progress";
    public static final String KEY_STATUS = "status";
    public static final String KEY_MODE = "mode";
    public static final String KEY_TOTAL_PACKETS = "total_packets";
    public static final String KEY_RECEIVED_PACKETS = "received_packets";

    public static final String KEY_FILE_ID = "file_id";
    public static final String KEY_PACKET_INDEX = "packet_index";
    public static final String KEY_PAYLOAD = "payload";
    public static final String KEY_CHECKSUM = "checksum";

    private static TransferDatabase instance;

    public static synchronized TransferDatabase getInstance(Context context) {
        if (instance == null) {
            instance = new TransferDatabase(context.getApplicationContext());
        }
        return instance;
    }

    public TransferDatabase(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String CREATE_TRANSFERS_TABLE = "CREATE TABLE " + TABLE_TRANSFERS + "("
                + KEY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + KEY_FILENAME + " TEXT,"
                + KEY_SIZE + " INTEGER,"
                + KEY_PROGRESS + " INTEGER,"
                + KEY_STATUS + " TEXT,"
                + KEY_MODE + " TEXT,"
                + KEY_TOTAL_PACKETS + " INTEGER,"
                + KEY_RECEIVED_PACKETS + " INTEGER" + ")";

        String CREATE_PACKETS_TABLE = "CREATE TABLE " + TABLE_PACKETS + "("
                + KEY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + KEY_FILE_ID + " TEXT,"
                + KEY_PACKET_INDEX + " INTEGER,"
                + KEY_TOTAL_PACKETS + " INTEGER,"
                + KEY_PAYLOAD + " TEXT,"
                + KEY_CHECKSUM + " INTEGER" + ")";

        db.execSQL(CREATE_TRANSFERS_TABLE);
        db.execSQL(CREATE_PACKETS_TABLE);

        seedSampleTransfers(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_TRANSFERS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_PACKETS);
        onCreate(db);
    }

    private void seedSampleTransfers(SQLiteDatabase db) {
        ContentValues t1 = new ContentValues();
        t1.put(KEY_FILENAME, "emergency_coordinates.json");
        t1.put(KEY_SIZE, 4096);
        t1.put(KEY_PROGRESS, 100);
        t1.put(KEY_STATUS, "COMPLETED");
        t1.put(KEY_MODE, "SMS_DATA");
        t1.put(KEY_TOTAL_PACKETS, 4);
        t1.put(KEY_RECEIVED_PACKETS, 4);
        db.insert(TABLE_TRANSFERS, null, t1);

        ContentValues t2 = new ContentValues();
        t2.put(KEY_FILENAME, "telemetry_stream.bin");
        t2.put(KEY_SIZE, 12288);
        t2.put(KEY_PROGRESS, 45);
        t2.put(KEY_STATUS, "TRANSFERRING");
        t2.put(KEY_MODE, "AUDIO_DATA");
        t2.put(KEY_TOTAL_PACKETS, 12);
        t2.put(KEY_RECEIVED_PACKETS, 5);
        db.insert(TABLE_TRANSFERS, null, t2);
    }

    public long insertTransfer(TransferItem item) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(KEY_FILENAME, item.getFilename());
        cv.put(KEY_SIZE, item.getSize());
        cv.put(KEY_PROGRESS, item.getProgress());
        cv.put(KEY_STATUS, item.getStatus());
        cv.put(KEY_MODE, item.getMode());
        cv.put(KEY_TOTAL_PACKETS, item.getTotalPackets());
        cv.put(KEY_RECEIVED_PACKETS, item.getReceivedPackets());
        return db.insert(TABLE_TRANSFERS, null, cv);
    }

    public List<TransferItem> getAllTransfers() {
        List<TransferItem> list = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT * FROM " + TABLE_TRANSFERS + " ORDER BY " + KEY_ID + " DESC", null);
        if (cursor.moveToFirst()) {
            do {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow(KEY_ID));
                String name = cursor.getString(cursor.getColumnIndexOrThrow(KEY_FILENAME));
                long size = cursor.getLong(cursor.getColumnIndexOrThrow(KEY_SIZE));
                int progress = cursor.getInt(cursor.getColumnIndexOrThrow(KEY_PROGRESS));
                String status = cursor.getString(cursor.getColumnIndexOrThrow(KEY_STATUS));
                String mode = cursor.getString(cursor.getColumnIndexOrThrow(KEY_MODE));
                int total = cursor.getInt(cursor.getColumnIndexOrThrow(KEY_TOTAL_PACKETS));
                int received = cursor.getInt(cursor.getColumnIndexOrThrow(KEY_RECEIVED_PACKETS));

                list.add(new TransferItem(id, name, size, progress, status, mode, total, received));
            } while (cursor.moveToNext());
        }
        cursor.close();
        return list;
    }

    public void savePacket(DataPacket packet) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(KEY_FILE_ID, packet.getFileId());
        cv.put(KEY_PACKET_INDEX, packet.getPacketIndex());
        cv.put(KEY_TOTAL_PACKETS, packet.getTotalPackets());
        cv.put(KEY_PAYLOAD, packet.getPayload());
        cv.put(KEY_CHECKSUM, packet.getChecksum());
        db.insert(TABLE_PACKETS, null, cv);
    }

    public List<DataPacket> getPacketsForFile(String fileId) {
        List<DataPacket> list = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.query(TABLE_PACKETS, null, KEY_FILE_ID + "=?", new String[]{fileId}, null, null, KEY_PACKET_INDEX + " ASC");
        if (cursor.moveToFirst()) {
            do {
                int index = cursor.getInt(cursor.getColumnIndexOrThrow(KEY_PACKET_INDEX));
                int total = cursor.getInt(cursor.getColumnIndexOrThrow(KEY_TOTAL_PACKETS));
                String payload = cursor.getString(cursor.getColumnIndexOrThrow(KEY_PAYLOAD));
                long checksum = cursor.getLong(cursor.getColumnIndexOrThrow(KEY_CHECKSUM));
                list.add(new DataPacket(fileId, index, total, payload, checksum));
            } while (cursor.moveToNext());
        }
        cursor.close();
        return list;
    }
}
