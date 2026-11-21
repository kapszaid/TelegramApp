package com.nexgram.messenger.ui.adapter;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.nexgram.messenger.R;
import com.nexgram.messenger.tdlib.TdLibAccountClient;
import com.nexgram.messenger.ui.helpers.TelegramAvatarHelper;
import com.nexgram.messenger.ui.helpers.TelegramDateFormatter;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.drinkless.tdlib.TdApi;

public class ContactAdapter extends RecyclerView.Adapter<ContactAdapter.ContactViewHolder> {

    public interface OnContactClickListener {
        void onContactClick(TdApi.User user);
    }

    public interface OnContactSelectionListener {
        void onSelectionChanged(Set<Long> selectedUserIds);
    }

    private final Context context;
    private final TdLibAccountClient client;
    private final boolean isPickerMode;
    private final OnContactClickListener clickListener;
    private final OnContactSelectionListener selectionListener;

    private final List<TdApi.User> users = new ArrayList<>();
    private final Set<Long> selectedUserIds = new HashSet<>();

    public ContactAdapter(Context context, TdLibAccountClient client, boolean isPickerMode,
                          OnContactClickListener clickListener, OnContactSelectionListener selectionListener) {
        this.context = context;
        this.client = client;
        this.isPickerMode = isPickerMode;
        this.clickListener = clickListener;
        this.selectionListener = selectionListener;
    }

    public ContactAdapter(Context context, boolean isPickerMode) {
        this(context, null, isPickerMode, null, null);
    }

    public boolean isPickerMode() {
        return isPickerMode;
    }

    public void setUsers(List<TdApi.User> newUsers) {
        updateUsers(newUsers);
    }

    public void toggleSelection(long userId) {
        if (selectedUserIds.contains(userId)) {
            selectedUserIds.remove(userId);
        } else {
            selectedUserIds.add(userId);
        }
        if (selectionListener != null) {
            selectionListener.onSelectionChanged(selectedUserIds);
        }
    }

    public boolean isUserSelected(long userId) {
        return selectedUserIds.contains(userId);
    }

    public void clearSelection() {
        selectedUserIds.clear();
        if (selectionListener != null) {
            selectionListener.onSelectionChanged(selectedUserIds);
        }
    }

    public void updateUsers(List<TdApi.User> newUsers) {
        this.users.clear();
        if (newUsers != null) {
            this.users.addAll(newUsers);
        }
        safeNotifyDataSetChanged();
    }

    private void safeNotifyDataSetChanged() {
        try {
            notifyDataSetChanged();
        } catch (Throwable ignored) {}
    }

    public Set<Long> getSelectedUserIds() {
        return new HashSet<>(selectedUserIds);
    }

    public int getSelectedCount() {
        return selectedUserIds.size();
    }

    @NonNull
    @Override
    public ContactViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_contact, parent, false);
        return new ContactViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ContactViewHolder holder, int position) {
        TdApi.User user = users.get(position);
        holder.bind(user, isPickerMode, selectedUserIds.contains(user.id));
    }

    @Override
    public int getItemCount() {
        return users.size();
    }

    class ContactViewHolder extends RecyclerView.ViewHolder {
        private final ImageView ivAvatarCircle;
        private final TextView tvAvatarInitials;
        private final ImageView ivAvatarPhoto;
        private final TextView tvName;
        private final TextView tvStatus;
        private final CheckBox cbSelect;

        ContactViewHolder(@NonNull View itemView) {
            super(itemView);
            ivAvatarCircle = itemView.findViewById(R.id.ivContactAvatarCircle);
            tvAvatarInitials = itemView.findViewById(R.id.tvContactAvatarInitials);
            ivAvatarPhoto = itemView.findViewById(R.id.ivContactAvatarPhoto);
            tvName = itemView.findViewById(R.id.tvContactName);
            tvStatus = itemView.findViewById(R.id.tvContactStatus);
            cbSelect = itemView.findViewById(R.id.cbSelectContact);
        }

        void bind(TdApi.User user, boolean pickerMode, boolean isSelected) {
            String fullName = (user.firstName != null ? user.firstName : "") +
                    (user.lastName != null && !user.lastName.isEmpty() ? " " + user.lastName : "");
            if (fullName.trim().isEmpty()) {
                fullName = user.phoneNumber != null && !user.phoneNumber.isEmpty() ? "+" + user.phoneNumber : "Deleted Account";
            }
            tvName.setText(fullName.trim());

            // Status formatting
            formatUserStatus(user.status);

            // Avatar setup
            int avatarColor = TelegramAvatarHelper.getAvatarColor(Math.abs((int) user.id));
            ivAvatarCircle.setColorFilter(avatarColor);
            tvAvatarInitials.setText(TelegramAvatarHelper.getInitials(fullName));
            tvAvatarInitials.setVisibility(View.VISIBLE);
            ivAvatarPhoto.setVisibility(View.GONE);

            // Photo download if available
            if (user.profilePhoto != null && user.profilePhoto.small != null) {
                TdApi.File smallFile = user.profilePhoto.small;
                if (smallFile.local != null && smallFile.local.isDownloadingCompleted && new File(smallFile.local.path).exists()) {
                    try {
                        ivAvatarPhoto.setImageBitmap(BitmapFactory.decodeFile(smallFile.local.path));
                        ivAvatarPhoto.setVisibility(View.VISIBLE);
                        tvAvatarInitials.setVisibility(View.GONE);
                    } catch (Exception ignored) {}
                } else if (client != null && smallFile.id > 0) {
                    client.downloadFile(smallFile.id, 1, null);
                }
            }

            // Picker Mode Checkbox
            if (pickerMode) {
                cbSelect.setVisibility(View.VISIBLE);
                cbSelect.setChecked(isSelected);
                cbSelect.setOnClickListener(v -> toggleSelection(user));
                itemView.setOnClickListener(v -> toggleSelection(user));
            } else {
                cbSelect.setVisibility(View.GONE);
                itemView.setOnClickListener(v -> {
                    if (clickListener != null) {
                        clickListener.onContactClick(user);
                    }
                });
            }
        }

        private void toggleSelection(TdApi.User user) {
            if (selectedUserIds.contains(user.id)) {
                selectedUserIds.remove(user.id);
                cbSelect.setChecked(false);
            } else {
                selectedUserIds.add(user.id);
                cbSelect.setChecked(true);
            }
            if (selectionListener != null) {
                selectionListener.onSelectionChanged(selectedUserIds);
            }
        }

        private void formatUserStatus(TdApi.UserStatus status) {
            if (status instanceof TdApi.UserStatusOnline) {
                tvStatus.setText("online");
                tvStatus.setTextColor(context.getColor(R.color.primary));
            } else if (status instanceof TdApi.UserStatusOffline) {
                int wasOnline = ((TdApi.UserStatusOffline) status).wasOnline;
                tvStatus.setText("last seen " + TelegramDateFormatter.formatChatTimestamp(wasOnline));
                tvStatus.setTextColor(context.getColor(R.color.text_secondary));
            } else if (status instanceof TdApi.UserStatusRecently) {
                tvStatus.setText("last seen recently");
                tvStatus.setTextColor(context.getColor(R.color.text_secondary));
            } else if (status instanceof TdApi.UserStatusLastWeek) {
                tvStatus.setText("last seen within a week");
                tvStatus.setTextColor(context.getColor(R.color.text_secondary));
            } else if (status instanceof TdApi.UserStatusLastMonth) {
                tvStatus.setText("last seen within a month");
                tvStatus.setTextColor(context.getColor(R.color.text_secondary));
            } else {
                tvStatus.setText("");
                tvStatus.setTextColor(context.getColor(R.color.text_secondary));
            }
        }
    }
}
