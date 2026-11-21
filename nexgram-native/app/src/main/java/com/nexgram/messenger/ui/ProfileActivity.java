package com.nexgram.messenger.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.nexgram.messenger.NexGramApplication;
import com.nexgram.messenger.R;
import com.nexgram.messenger.auth.AccountManager;
import com.nexgram.messenger.tdlib.TdLibAccountClient;
import com.nexgram.messenger.tdlib.TdLibManager;
import com.nexgram.messenger.ui.helpers.TelegramAvatarHelper;

import org.drinkless.tdlib.TdApi;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class ProfileActivity extends AppCompatActivity {
    private static final String TAG = "ProfileActivity";

    private String accountId;
    private TdLibAccountClient client;

    private Toolbar toolbar;
    private ImageView ivAvatarCircle;
    private TextView tvAvatarInitials;
    private ImageView ivAvatarPhoto;
    private ImageView btnChangePhoto;
    private TextView tvName;
    private TextView tvStatus;

    private LinearLayout rowPhone;
    private TextView tvPhone;

    private LinearLayout rowUsername;
    private TextView tvUsername;

    private LinearLayout rowBio;
    private TextView tvBio;

    private LinearLayout btnEditName;
    private LinearLayout btnShareProfile;
    private ProgressBar pbLoading;

    private TdApi.User currentUser;
    private String currentBio = "";

    private final ActivityResultLauncher<String> photoPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            this::handleSelectedPhotoUri
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        accountId = getIntent().getStringExtra("account_id");
        resolveAccountClient();
        initViews();
        loadMyProfile();
    }

    private void resolveAccountClient() {
        if (accountId == null || accountId.isEmpty()) {
            AccountManager manager = NexGramApplication.getInstance().getAccountManager();
            AccountManager.AccountItem active = manager != null ? manager.getActiveAccount() : null;
            if (active != null) {
                accountId = active.id;
            }
        }
        if (accountId != null) {
            client = TdLibManager.getInstance().getClient(accountId);
        }
    }

    private void initViews() {
        toolbar = findViewById(R.id.toolbarProfile);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowTitleEnabled(true);
        }

        ivAvatarCircle = findViewById(R.id.ivMyAvatarCircle);
        tvAvatarInitials = findViewById(R.id.tvMyAvatarInitials);
        ivAvatarPhoto = findViewById(R.id.ivMyAvatarPhoto);
        btnChangePhoto = findViewById(R.id.btnChangePhoto);
        tvName = findViewById(R.id.tvMyName);
        tvStatus = findViewById(R.id.tvMyStatus);

        rowPhone = findViewById(R.id.rowMyPhone);
        tvPhone = findViewById(R.id.tvMyPhone);

        rowUsername = findViewById(R.id.rowMyUsername);
        tvUsername = findViewById(R.id.tvMyUsername);

        rowBio = findViewById(R.id.rowMyBio);
        tvBio = findViewById(R.id.tvMyBio);

        btnEditName = findViewById(R.id.btnEditName);
        btnShareProfile = findViewById(R.id.btnShareProfile);
        pbLoading = findViewById(R.id.pbProfileLoading);

        btnChangePhoto.setOnClickListener(v -> photoPickerLauncher.launch("image/*"));
        btnEditName.setOnClickListener(v -> showEditNameDialog());
        rowUsername.setOnClickListener(v -> showEditUsernameDialog());
        rowBio.setOnClickListener(v -> showEditBioDialog());
        rowPhone.setOnClickListener(v -> copyPhoneToClipboard());
        btnShareProfile.setOnClickListener(v -> shareProfileLink());
    }

    private void loadMyProfile() {
        if (client == null) return;
        pbLoading.setVisibility(View.VISIBLE);

        client.getMe(object -> runOnUiThread(() -> {
            pbLoading.setVisibility(View.GONE);
            if (object instanceof TdApi.User) {
                currentUser = (TdApi.User) object;
                bindUserData(currentUser);
                loadUserFullInfo(currentUser.id);
            }
        }));
    }

    private void loadUserFullInfo(long userId) {
        if (client == null) return;
        client.getUserFullInfo(userId, object -> runOnUiThread(() -> {
            if (object instanceof TdApi.UserFullInfo) {
                TdApi.UserFullInfo fullInfo = (TdApi.UserFullInfo) object;
                currentBio = fullInfo.bio != null ? fullInfo.bio.text : "";
                tvBio.setText(!currentBio.isEmpty() ? currentBio : "No bio set");
            }
        }));
    }

    private void bindUserData(TdApi.User user) {
        String firstName = user.firstName != null ? user.firstName : "";
        String lastName = user.lastName != null ? user.lastName : "";
        String fullName = (firstName + " " + lastName).trim();
        tvName.setText(!fullName.isEmpty() ? fullName : "User");

        String phone = user.phoneNumber != null && !user.phoneNumber.isEmpty() ? "+" + user.phoneNumber : "Hidden";
        tvPhone.setText(phone);

        String username = "";
        if (user.usernames != null && user.usernames.activeUsernames.length > 0) {
            username = "@" + user.usernames.activeUsernames[0];
        }
        tvUsername.setText(!username.isEmpty() ? username : "Set Username");

        // Avatar
        tvAvatarInitials.setText(TelegramAvatarHelper.getInitials(fullName));
        ivAvatarCircle.setColorFilter(TelegramAvatarHelper.getAvatarColor(Math.abs((int) user.id)));

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

    private void showEditNameDialog() {
        if (currentUser == null) return;
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_contact, null);
        EditText etFirst = dialogView.findViewById(R.id.etAddContactFirst);
        EditText etLast = dialogView.findViewById(R.id.etAddContactLast);
        View rowPhone = dialogView.findViewById(R.id.etAddContactPhone);
        if (rowPhone != null) rowPhone.setVisibility(View.GONE);

        etFirst.setText(currentUser.firstName);
        etLast.setText(currentUser.lastName);

        new AlertDialog.Builder(this)
                .setTitle("Edit Name")
                .setView(dialogView)
                .setPositiveButton("Save", (dialog, which) -> {
                    String first = etFirst.getText().toString().trim();
                    String last = etLast.getText().toString().trim();
                    if (first.isEmpty()) {
                        Toast.makeText(this, "First name cannot be empty", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    pbLoading.setVisibility(View.VISIBLE);
                    client.setName(first, last, result -> runOnUiThread(() -> {
                        pbLoading.setVisibility(View.GONE);
                        if (result instanceof TdApi.Ok) {
                            Toast.makeText(this, "Name updated", Toast.LENGTH_SHORT).show();
                            loadMyProfile();
                        } else if (result instanceof TdApi.Error) {
                            Toast.makeText(this, ((TdApi.Error) result).message, Toast.LENGTH_LONG).show();
                        }
                    }));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showEditUsernameDialog() {
        final EditText input = new EditText(this);
        input.setHint("username (a-z, 0-9, _)");
        if (currentUser != null && currentUser.usernames != null && currentUser.usernames.activeUsernames.length > 0) {
            input.setText(currentUser.usernames.activeUsernames[0]);
        }

        new AlertDialog.Builder(this)
                .setTitle("Set Username")
                .setMessage("You can choose a username on Telegram. Other people will be able to find you by this username.")
                .setView(input)
                .setPositiveButton("Save", (dialog, which) -> {
                    String username = input.getText().toString().trim().replace("@", "");
                    pbLoading.setVisibility(View.VISIBLE);
                    client.setUsername(username, result -> runOnUiThread(() -> {
                        pbLoading.setVisibility(View.GONE);
                        if (result instanceof TdApi.Ok) {
                            Toast.makeText(this, "Username updated", Toast.LENGTH_SHORT).show();
                            loadMyProfile();
                        } else if (result instanceof TdApi.Error) {
                            Toast.makeText(this, ((TdApi.Error) result).message, Toast.LENGTH_LONG).show();
                        }
                    }));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showEditBioDialog() {
        final EditText input = new EditText(this);
        input.setHint("A few words about yourself");
        input.setText(currentBio);

        new AlertDialog.Builder(this)
                .setTitle("Edit Bio")
                .setMessage("Any details such as age, occupation or city.")
                .setView(input)
                .setPositiveButton("Save", (dialog, which) -> {
                    String bio = input.getText().toString().trim();
                    pbLoading.setVisibility(View.VISIBLE);
                    client.setBio(bio, result -> runOnUiThread(() -> {
                        pbLoading.setVisibility(View.GONE);
                        if (result instanceof TdApi.Ok) {
                            Toast.makeText(this, "Bio updated", Toast.LENGTH_SHORT).show();
                            loadMyProfile();
                        } else if (result instanceof TdApi.Error) {
                            Toast.makeText(this, ((TdApi.Error) result).message, Toast.LENGTH_LONG).show();
                        }
                    }));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void handleSelectedPhotoUri(Uri uri) {
        if (uri == null || client == null) return;
        pbLoading.setVisibility(View.VISIBLE);

        new Thread(() -> {
            try {
                InputStream is = getContentResolver().openInputStream(uri);
                File tempFile = new File(getCacheDir(), "new_profile_" + System.currentTimeMillis() + ".jpg");
                FileOutputStream fos = new FileOutputStream(tempFile);
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, read);
                }
                fos.flush();
                fos.close();
                is.close();

                client.setProfilePhoto(tempFile.getAbsolutePath(), result -> runOnUiThread(() -> {
                    pbLoading.setVisibility(View.GONE);
                    if (result instanceof TdApi.Ok) {
                        Toast.makeText(this, "Profile photo updated", Toast.LENGTH_SHORT).show();
                        loadMyProfile();
                    } else if (result instanceof TdApi.Error) {
                        Toast.makeText(this, ((TdApi.Error) result).message, Toast.LENGTH_LONG).show();
                    }
                }));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    pbLoading.setVisibility(View.GONE);
                    Toast.makeText(this, "Failed to load image: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void copyPhoneToClipboard() {
        if (currentUser == null || currentUser.phoneNumber == null) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Phone Number", "+" + currentUser.phoneNumber));
            Toast.makeText(this, "Phone number copied to clipboard", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareProfileLink() {
        String handle = "";
        if (currentUser != null && currentUser.usernames != null && currentUser.usernames.activeUsernames.length > 0) {
            handle = currentUser.usernames.activeUsernames[0];
        }
        String shareText = !handle.isEmpty() ? "https://t.me/" + handle : "https://t.me/";
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("text/plain");
        shareIntent.putExtra(Intent.EXTRA_TEXT, shareText);
        startActivity(Intent.createChooser(shareIntent, "Share Profile"));
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
