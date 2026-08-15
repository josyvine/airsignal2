package com.example.models;

public class TransferItem {
    private long id;
    private String filename;
    private long size;
    private int progress; // 0 to 100
    private String status; // "PENDING", "TRANSFERRING", "COMPLETED", "FAILED"
    private String mode; // "SMS_DATA", "AUDIO_DATA"
    private int totalPackets;
    private int receivedPackets;

    public TransferItem(long id, String filename, long size, int progress, String status, String mode, int totalPackets, int receivedPackets) {
        this.id = id;
        this.filename = filename;
        this.size = size;
        this.progress = progress;
        this.status = status;
        this.mode = mode;
        this.totalPackets = totalPackets;
        this.receivedPackets = receivedPackets;
    }

    public long getId() { return id; }
    public String getFilename() { return filename; }
    public long getSize() { return size; }
    public int getProgress() { return progress; }
    public String getStatus() { return status; }
    public String getMode() { return mode; }
    public int getTotalPackets() { return totalPackets; }
    public int getReceivedPackets() { return receivedPackets; }

    public void setProgress(int progress) { this.progress = progress; }
    public void setStatus(String status) { this.status = status; }
    public void setReceivedPackets(int receivedPackets) { this.receivedPackets = receivedPackets; }
}
