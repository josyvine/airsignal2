package com.example.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.R;
import com.example.models.TransferItem;

import java.util.List;

public class TransferAdapter extends RecyclerView.Adapter<TransferAdapter.ViewHolder> {

    private List<TransferItem> transferList;

    public TransferAdapter(List<TransferItem> transferList) {
        this.transferList = transferList;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_transfer, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        TransferItem item = transferList.get(position);
        holder.tvFilename.setText(item.getFilename());
        holder.tvMode.setText(item.getMode() + " • " + item.getReceivedPackets() + "/" + item.getTotalPackets() + " pkts");
        holder.tvProgressText.setText(item.getProgress() + "% (" + item.getStatus() + ")");
        holder.progressBar.setProgress(item.getProgress());
    }

    @Override
    public int getItemCount() {
        return transferList.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvFilename, tvMode, tvProgressText;
        ProgressBar progressBar;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvFilename = itemView.findViewById(R.id.tvFilename);
            tvMode = itemView.findViewById(R.id.tvTransferMode);
            tvProgressText = itemView.findViewById(R.id.tvTransferProgressText);
            progressBar = itemView.findViewById(R.id.progressBarTransfer);
        }
    }
}
