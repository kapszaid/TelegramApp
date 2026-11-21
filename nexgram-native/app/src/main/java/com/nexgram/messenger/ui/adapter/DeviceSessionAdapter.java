package com.nexgram.messenger.ui.adapter;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.nexgram.messenger.R;
import com.nexgram.messenger.ui.helpers.TelegramDateFormatter;

import org.drinkless.tdlib.TdApi;

import java.util.ArrayList;
import java.util.List;

public class DeviceSessionAdapter extends RecyclerView.Adapter<DeviceSessionAdapter.SessionViewHolder> {

    public interface OnSessionTerminateListener {
        void onTerminate(TdApi.Session session);
    }

    private final Context context;
    private final List<TdApi.Session> sessions = new ArrayList<>();
    private final OnSessionTerminateListener listener;

    public DeviceSessionAdapter(Context context, OnSessionTerminateListener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void setSessions(List<TdApi.Session> newSessions) {
        sessions.clear();
        if (newSessions != null) {
            sessions.addAll(newSessions);
        }
        safeNotifyDataSetChanged();
    }

    private void safeNotifyDataSetChanged() {
        try {
            notifyDataSetChanged();
        } catch (Throwable ignored) {}
    }

    @NonNull
    @Override
    public SessionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_device_session, parent, false);
        return new SessionViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SessionViewHolder holder, int position) {
        TdApi.Session session = sessions.get(position);
        holder.bind(session);
    }

    @Override
    public int getItemCount() {
        return sessions.size();
    }

    class SessionViewHolder extends RecyclerView.ViewHolder {
        final TextView tvTitle;
        final TextView tvSubtitle;
        final TextView tvLocation;
        final Button btnTerminate;

        SessionViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tvDeviceTitle);
            tvSubtitle = itemView.findViewById(R.id.tvDeviceSubtitle);
            tvLocation = itemView.findViewById(R.id.tvDeviceLocation);
            btnTerminate = itemView.findViewById(R.id.btnTerminateSession);
        }

        void bind(TdApi.Session session) {
            String model = session.deviceModel != null && !session.deviceModel.isEmpty() ? session.deviceModel : "Unknown Device";
            tvTitle.setText(model);

            String app = (session.applicationName != null ? session.applicationName : "Telegram") +
                    (session.applicationVersion != null ? " " + session.applicationVersion : "") +
                    (session.platform != null ? ", " + session.platform : "");
            tvSubtitle.setText(app);

            String loc = (session.location != null && !session.location.isEmpty() ? session.location : "Unknown location") +
                    (session.ipAddress != null && !session.ipAddress.isEmpty() ? " • " + session.ipAddress : "");
            if (session.lastActiveDate > 0) {
                loc += " • " + TelegramDateFormatter.formatChatTimestamp(session.lastActiveDate);
            }
            tvLocation.setText(loc);

            btnTerminate.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onTerminate(session);
                }
            });
        }
    }
}
