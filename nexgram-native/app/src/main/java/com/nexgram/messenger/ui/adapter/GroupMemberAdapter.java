package com.nexgram.messenger.ui.adapter;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.nexgram.messenger.R;
import com.nexgram.messenger.tdlib.TdLibAccountClient;

import org.drinkless.tdlib.TdApi;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapter for displaying members in a Telegram group or channel.
 */
public class GroupMemberAdapter extends RecyclerView.Adapter<GroupMemberAdapter.MemberViewHolder> {

    public interface OnMemberClickListener {
        void onMemberClick(TdApi.ChatMember member, TdApi.User user);
        void onMemberLongClick(TdApi.ChatMember member, TdApi.User user);
    }

    private final Context context;
    private final TdLibAccountClient client;
    private final List<TdApi.ChatMember> members = new ArrayList<>();
    private final Map<Long, TdApi.User> userCache = new HashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private OnMemberClickListener listener;

    public GroupMemberAdapter(Context context, TdLibAccountClient client) {
        this.context = context;
        this.client = client;
    }

    public void setListener(OnMemberClickListener listener) {
        this.listener = listener;
    }

    public void setMembers(List<TdApi.ChatMember> newMembers) {
        members.clear();
        if (newMembers != null) {
            members.addAll(newMembers);
        }
        notifyDataSetChanged();
    }

    public void addMembers(List<TdApi.ChatMember> newMembers) {
        if (newMembers == null || newMembers.isEmpty()) return;
        int start = members.size();
        members.addAll(newMembers);
        notifyItemRangeInserted(start, newMembers.size());
    }

    @NonNull
    @Override
    public MemberViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_group_member, parent, false);
        return new MemberViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MemberViewHolder holder, int position) {
        TdApi.ChatMember member = members.get(position);
        holder.bind(member);
    }

    @Override
    public int getItemCount() {
        return members.size();
    }

    class MemberViewHolder extends RecyclerView.ViewHolder {
        final ImageView ivAvatarCircle;
        final TextView tvAvatarInitials;
        final ImageView ivAvatarPhoto;
        final TextView tvName;
        final TextView tvStatus;
        final TextView tvRole;

        MemberViewHolder(@NonNull View itemView) {
            super(itemView);
            ivAvatarCircle = itemView.findViewById(R.id.ivMemberAvatarCircle);
            tvAvatarInitials = itemView.findViewById(R.id.tvMemberAvatarInitials);
            ivAvatarPhoto = itemView.findViewById(R.id.ivMemberAvatarPhoto);
            tvName = itemView.findViewById(R.id.tvMemberName);
            tvStatus = itemView.findViewById(R.id.tvMemberStatus);
            tvRole = itemView.findViewById(R.id.tvMemberRole);
        }

        void bind(TdApi.ChatMember member) {
            if (member == null) return;

            // Determine role tag
            if (member.status instanceof TdApi.ChatMemberStatusCreator) {
                tvRole.setVisibility(View.VISIBLE);
                tvRole.setText("owner");
                tvRole.setTextColor(Color.parseColor("#4A90E2"));
            } else if (member.status instanceof TdApi.ChatMemberStatusAdministrator) {
                tvRole.setVisibility(View.VISIBLE);
                tvRole.setText("admin");
                tvRole.setTextColor(Color.parseColor("#00B0FF"));
            } else {
                tvRole.setVisibility(View.GONE);
            }

            long userId = 0;
            if (member.memberId instanceof TdApi.MessageSenderUser) {
                userId = ((TdApi.MessageSenderUser) member.memberId).userId;
            }

            if (userId == 0) {
                tvName.setText("Channel");
                tvStatus.setText("");
                tvAvatarInitials.setText("CH");
                return;
            }

            final long finalUserId = userId;
            TdApi.User cachedUser = userCache.get(userId);
            if (cachedUser != null) {
                populateUserInfo(cachedUser, member);
            } else {
                tvName.setText("User " + userId);
                tvStatus.setText("loading...");
                tvAvatarInitials.setText("U");
                ivAvatarPhoto.setVisibility(View.GONE);

                if (client != null) {
                    client.getUser(userId, object -> {
                        if (object instanceof TdApi.User) {
                            TdApi.User user = (TdApi.User) object;
                            userCache.put(finalUserId, user);
                            mainHandler.post(() -> {
                                if (getBindingAdapterPosition() != RecyclerView.NO_POSITION) {
                                    populateUserInfo(user, member);
                                }
                            });
                        }
                    });
                }
            }

            itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onMemberClick(member, userCache.get(finalUserId));
                }
            });

            itemView.setOnLongClickListener(v -> {
                if (listener != null) {
                    listener.onMemberLongClick(member, userCache.get(finalUserId));
                    return true;
                }
                return false;
            });
        }

        private void populateUserInfo(TdApi.User user, TdApi.ChatMember member) {
            String firstName = user.firstName != null ? user.firstName : "";
            String lastName = user.lastName != null ? user.lastName : "";
            String fullName = (firstName + " " + lastName).trim();
            if (fullName.isEmpty()) {
                fullName = user.usernames != null && user.usernames.activeUsernames.length > 0
                        ? "@" + user.usernames.activeUsernames[0]
                        : "User " + user.id;
            }
            tvName.setText(fullName);

            // Status
            String statusStr = formatUserStatus(user.status);
            tvStatus.setText(statusStr);

            // Initials
            String initials = getInitials(fullName);
            tvAvatarInitials.setText(initials);
            ivAvatarCircle.setColorFilter(getColorForId(user.id));

            // Profile photo
            if (user.profilePhoto != null && user.profilePhoto.small != null && user.profilePhoto.small.local != null
                    && user.profilePhoto.small.local.isDownloadingCompleted
                    && new File(user.profilePhoto.small.local.path).exists()) {
                ivAvatarPhoto.setImageBitmap(BitmapFactory.decodeFile(user.profilePhoto.small.local.path));
                ivAvatarPhoto.setVisibility(View.VISIBLE);
                tvAvatarInitials.setVisibility(View.GONE);
            } else {
                ivAvatarPhoto.setVisibility(View.GONE);
                tvAvatarInitials.setVisibility(View.VISIBLE);
            }
        }
    }

    private static String getInitials(String name) {
        if (name == null || name.isEmpty()) return "?";
        String[] parts = name.trim().split("\\s+");
        if (parts.length >= 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
            return (parts[0].substring(0, 1) + parts[1].substring(0, 1)).toUpperCase();
        }
        return name.substring(0, Math.min(2, name.length())).toUpperCase();
    }

    private static int getColorForId(long id) {
        int[] colors = {
                Color.parseColor("#E17076"),
                Color.parseColor("#FAA774"),
                Color.parseColor("#A695E7"),
                Color.parseColor("#7BC862"),
                Color.parseColor("#6EC9CB"),
                Color.parseColor("#65AADD"),
                Color.parseColor("#EE7AAE"),
                Color.parseColor("#2196F3")
        };
        return colors[(int) (Math.abs(id) % colors.length)];
    }

    private static String formatUserStatus(TdApi.UserStatus status) {
        if (status instanceof TdApi.UserStatusOnline) {
            return "online";
        } else if (status instanceof TdApi.UserStatusOffline) {
            return "last seen recently";
        } else if (status instanceof TdApi.UserStatusRecently) {
            return "last seen recently";
        } else if (status instanceof TdApi.UserStatusLastWeek) {
            return "last seen within a week";
        } else if (status instanceof TdApi.UserStatusLastMonth) {
            return "last seen within a month";
        }
        return "offline";
    }
}
