package com.nexgram.messenger.ui.adapter;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.nexgram.messenger.R;
import com.nexgram.messenger.auth.AccountManager;
import com.nexgram.messenger.ui.helpers.TelegramAvatarHelper;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class DrawerAccountAdapter extends RecyclerView.Adapter<DrawerAccountAdapter.AccountViewHolder> {

    public interface OnAccountClickListener {
        void onAccountClick(AccountManager.AccountItem account);
        void onAccountLongClick(AccountManager.AccountItem account);
    }

    private final Context context;
    private final List<AccountManager.AccountItem> accounts = new ArrayList<>();
    private String activeAccountId;
    private final OnAccountClickListener listener;

    public DrawerAccountAdapter(Context context, OnAccountClickListener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void updateAccounts(List<AccountManager.AccountItem> newAccounts, String activeId) {
        this.accounts.clear();
        if (newAccounts != null) {
            this.accounts.addAll(newAccounts);
        }
        this.activeAccountId = activeId;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public AccountViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_drawer_account, parent, false);
        return new AccountViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AccountViewHolder holder, int position) {
        AccountManager.AccountItem item = accounts.get(position);
        holder.bind(item, item.id != null && item.id.equals(activeAccountId), listener);
    }

    @Override
    public int getItemCount() {
        return accounts.size();
    }

    static class AccountViewHolder extends RecyclerView.ViewHolder {
        private final ImageView ivAvatarCircle;
        private final TextView tvAvatarInitials;
        private final ImageView ivAvatarPhoto;
        private final TextView tvName;
        private final TextView tvPhone;
        private final ImageView ivCheck;

        AccountViewHolder(@NonNull View itemView) {
            super(itemView);
            ivAvatarCircle = itemView.findViewById(R.id.ivAccountAvatarCircle);
            tvAvatarInitials = itemView.findViewById(R.id.tvAccountAvatarInitials);
            ivAvatarPhoto = itemView.findViewById(R.id.ivAccountAvatarPhoto);
            tvName = itemView.findViewById(R.id.tvAccountName);
            tvPhone = itemView.findViewById(R.id.tvAccountPhone);
            ivCheck = itemView.findViewById(R.id.ivAccountCheck);
        }

        void bind(AccountManager.AccountItem item, boolean isActive, OnAccountClickListener listener) {
            tvName.setText(item.name != null && !item.name.isEmpty() ? item.name : "Telegram User");
            tvPhone.setText(item.phone != null && !item.phone.isEmpty() ? item.phone : "");

            int color = TelegramAvatarHelper.getAvatarColor(Math.abs((item.name != null ? item.name : item.id).hashCode()));
            ivAvatarCircle.setColorFilter(color);

            if (item.avatarPath != null && !item.avatarPath.isEmpty() && new File(item.avatarPath).exists()) {
                try {
                    ivAvatarPhoto.setImageBitmap(BitmapFactory.decodeFile(item.avatarPath));
                    ivAvatarPhoto.setVisibility(View.VISIBLE);
                    tvAvatarInitials.setVisibility(View.GONE);
                } catch (Exception e) {
                    ivAvatarPhoto.setVisibility(View.GONE);
                    tvAvatarInitials.setVisibility(View.VISIBLE);
                    tvAvatarInitials.setText(TelegramAvatarHelper.getInitials(item.name));
                }
            } else {
                ivAvatarPhoto.setVisibility(View.GONE);
                tvAvatarInitials.setVisibility(View.VISIBLE);
                tvAvatarInitials.setText(TelegramAvatarHelper.getInitials(item.name));
            }

            ivCheck.setVisibility(isActive ? View.VISIBLE : View.GONE);

            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onAccountClick(item);
                }
            });

            itemView.setOnLongClickListener(v -> {
                if (listener != null) {
                    listener.onAccountLongClick(item);
                }
                return true;
            });
        }
    }
}
