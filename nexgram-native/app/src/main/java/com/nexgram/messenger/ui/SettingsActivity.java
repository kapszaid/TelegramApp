package com.nexgram.messenger.ui;

import android.content.Intent;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.cardview.widget.CardView;

import com.nexgram.messenger.NexGramApplication;
import com.nexgram.messenger.R;
import com.nexgram.messenger.auth.AccountManager;
import com.nexgram.messenger.proxy.ProxyController;
import com.nexgram.messenger.tdlib.TdLibAccountClient;
import com.nexgram.messenger.tdlib.TdLibManager;
import com.nexgram.messenger.ui.helpers.ProxyDialogHelper;
import com.nexgram.messenger.ui.helpers.TelegramAvatarHelper;

import org.drinkless.tdlib.TdApi;

import java.io.File;

/**
 * Recreates official Telegram Android Settings screen:
 * - Profile header linking to ProfileActivity
 * - Notifications & Sounds
 * - Privacy & Security
 * - Data & Storage
 * - Chat Settings (Appearance)
 * - Devices
 * - Language
 * - Proxy Settings (account isolated)
 * - Help / About & Licenses
 */
public class SettingsActivity extends AppCompatActivity {
    private static final String TAG = "SettingsActivity";

    private Toolbar toolbar;
    private CardView cardAccountProfile;
    private ImageView ivAvatarCircle;
    private TextView tvAvatarInitials;
    private ImageView ivAvatarPhoto;
    private TextView tvSettingsUser;
    private TextView tvSettingsPhone;
    private TextView tvSettingsUsername;

    private LinearLayout rowNotifications;
    private LinearLayout rowPrivacy;
    private LinearLayout rowDataStorage;
    private LinearLayout rowChatSettings;
    private LinearLayout rowDevices;
    private LinearLayout rowLanguage;
    private LinearLayout cardProxy;
    private TextView tvProxyStatus;

    private LinearLayout rowAboutLicenses;
    private LinearLayout cardBackup;
    private Button btnLogout;

    private String activeAccountId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        toolbar = findViewById(R.id.toolbarSettings);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.title_settings);
        }

        initViews();
        loadProfile();
    }

    private void initViews() {
        cardAccountProfile = findViewById(R.id.cardAccountProfile);
        ivAvatarCircle = findViewById(R.id.ivSettingsAvatarCircle);
        tvAvatarInitials = findViewById(R.id.tvSettingsAvatarInitials);
        ivAvatarPhoto = findViewById(R.id.ivSettingsAvatarPhoto);
        tvSettingsUser = findViewById(R.id.tvSettingsUser);
        tvSettingsPhone = findViewById(R.id.tvSettingsPhone);
        tvSettingsUsername = findViewById(R.id.tvSettingsUsername);

        rowNotifications = findViewById(R.id.rowSettingNotifications);
        rowPrivacy = findViewById(R.id.rowSettingPrivacy);
        rowDataStorage = findViewById(R.id.rowSettingDataStorage);
        rowChatSettings = findViewById(R.id.rowSettingChatSettings);
        rowDevices = findViewById(R.id.rowSettingDevices);
        rowLanguage = findViewById(R.id.rowSettingLanguage);
        cardProxy = findViewById(R.id.cardProxySettings);
        tvProxyStatus = findViewById(R.id.tvProxyStatus);

        rowAboutLicenses = findViewById(R.id.rowAboutLicenses);
        cardBackup = findViewById(R.id.cardBackup);
        btnLogout = findViewById(R.id.btnSettingsLogout);

        cardAccountProfile.setOnClickListener(v -> {
            Intent intent = new Intent(SettingsActivity.this, ProfileActivity.class);
            if (activeAccountId != null) {
                intent.putExtra("account_id", activeAccountId);
            }
            startActivity(intent);
        });

        rowNotifications.setOnClickListener(v -> {
            Intent intent = new Intent(SettingsActivity.this, NotificationsSettingsActivity.class);
            if (activeAccountId != null) intent.putExtra("account_id", activeAccountId);
            startActivity(intent);
        });

        rowPrivacy.setOnClickListener(v -> {
            Intent intent = new Intent(SettingsActivity.this, PrivacySettingsActivity.class);
            if (activeAccountId != null) intent.putExtra("account_id", activeAccountId);
            startActivity(intent);
        });

        rowDataStorage.setOnClickListener(v -> {
            Intent intent = new Intent(SettingsActivity.this, DataStorageActivity.class);
            if (activeAccountId != null) intent.putExtra("account_id", activeAccountId);
            startActivity(intent);
        });

        rowChatSettings.setOnClickListener(v -> {
            Intent intent = new Intent(SettingsActivity.this, ChatSettingsActivity.class);
            if (activeAccountId != null) intent.putExtra("account_id", activeAccountId);
            startActivity(intent);
        });

        rowDevices.setOnClickListener(v -> {
            Intent intent = new Intent(SettingsActivity.this, DevicesActivity.class);
            if (activeAccountId != null) intent.putExtra("account_id", activeAccountId);
            startActivity(intent);
        });

        rowLanguage.setOnClickListener(v -> showLanguageDialog());
        cardProxy.setOnClickListener(v -> showProxyConfigurationDialog());
        cardBackup.setOnClickListener(v -> showBackupInfoDialog());
        rowAboutLicenses.setOnClickListener(v -> showLicensesDialog());
        btnLogout.setOnClickListener(v -> confirmLogout());
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadProfile();
    }

    private void loadProfile() {
        AccountManager manager = NexGramApplication.getInstance().getAccountManager();
        AccountManager.AccountItem active = manager != null ? manager.getActiveAccount() : null;
        if (active != null) {
            activeAccountId = active.id;
            tvSettingsUser.setText(active.name != null && !active.name.isEmpty() ? active.name : "Telegram User");
            tvSettingsPhone.setText(active.phone != null && !active.phone.isEmpty() ? active.phone : "No Phone");
            tvSettingsUsername.setText(active.username != null && !active.username.isEmpty() ? "@" + active.username : "Set Username");
            btnLogout.setVisibility(View.VISIBLE);

            tvAvatarInitials.setText(TelegramAvatarHelper.getInitials(active.name));
            ivAvatarCircle.setColorFilter(TelegramAvatarHelper.getAvatarColor(Math.abs((active.name != null ? active.name : active.id).hashCode())));

            if (active.avatarPath != null && !active.avatarPath.isEmpty() && new File(active.avatarPath).exists()) {
                try {
                    ivAvatarPhoto.setImageBitmap(BitmapFactory.decodeFile(active.avatarPath));
                    ivAvatarPhoto.setVisibility(View.VISIBLE);
                    tvAvatarInitials.setVisibility(View.GONE);
                } catch (Exception ignored) {
                    ivAvatarPhoto.setVisibility(View.GONE);
                    tvAvatarInitials.setVisibility(View.VISIBLE);
                }
            } else {
                ivAvatarPhoto.setVisibility(View.GONE);
                tvAvatarInitials.setVisibility(View.VISIBLE);
            }

            // Sync fresh details from TDLib
            TdLibManager tdManager = NexGramApplication.getInstance().getTdLibManager();
            if (tdManager != null) {
                TdLibAccountClient client = tdManager.getClient(active.id);
                if (client != null) {
                    client.getMe(res -> {
                        if (res instanceof TdApi.User) {
                            TdApi.User user = (TdApi.User) res;
                            runOnUiThread(() -> {
                                String fName = user.firstName != null ? user.firstName : "";
                                String lName = user.lastName != null ? user.lastName : "";
                                String full = (fName + " " + lName).trim();
                                if (!full.isEmpty()) tvSettingsUser.setText(full);
                                if (user.phoneNumber != null) tvSettingsPhone.setText("+" + user.phoneNumber);
                                if (user.usernames != null && user.usernames.activeUsernames.length > 0) {
                                    tvSettingsUsername.setText("@" + user.usernames.activeUsernames[0]);
                                }
                            });
                        }
                    });
                }
            }
        } else {
            activeAccountId = null;
            tvSettingsUser.setText("No Active User");
            tvSettingsPhone.setText("Tap to log in with Telegram");
            tvSettingsUsername.setText("");
            btnLogout.setVisibility(View.GONE);
        }

        ProxyController proxy = NexGramApplication.getInstance().getProxyController();
        String targetId = active != null ? active.id : AuthActivity.ACCOUNT_ID;
        ProxyController.ProxyMetadata meta = (proxy != null) ? proxy.getProxyMetadata(targetId) : null;

        if (meta != null && meta.enabled && meta.type != ProxyController.ProxyType.DIRECT) {
            String latency = meta.lastLatencyMs > 0 ? " (" + meta.lastLatencyMs + " ms)" : "";
            tvProxyStatus.setText("Connected: " + meta.type + " " + meta.host + ":" + meta.port + latency);
        } else {
            tvProxyStatus.setText("Direct Connection (Proxy Disabled)");
        }
    }

    private void showProxyConfigurationDialog() {
        AccountManager manager = NexGramApplication.getInstance().getAccountManager();
        AccountManager.AccountItem active = manager != null ? manager.getActiveAccount() : null;
        String targetAccountId = active != null ? active.id : AuthActivity.DEFAULT_ACCOUNT_ID;

        ProxyDialogHelper.showProxyDialog(this, targetAccountId, this::loadProfile);
    }

    private void showLanguageDialog() {
        String[] languages = {"English", "Español", "Русский", "हिन्दी", "Deutsch", "Français", "العربية"};
        new AlertDialog.Builder(this)
                .setTitle("Language")
                .setSingleChoiceItems(languages, 0, (dialog, which) -> {
                    Toast.makeText(this, "Language: " + languages[which], Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showLicensesDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Open Source Licenses")
                .setMessage("NexGram incorporates:\n\n" +
                        "• TDLib (Telegram Database Library) — Boost Software License 1.0\n" +
                        "• Android Jetpack & Material Components — Apache License 2.0\n" +
                        "• OpenSSL / BoringSSL Cryptography — OpenSSL License")
                .setPositiveButton("Close", null)
                .show();
    }

    private void showBackupInfoDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.title_backup)
                .setMessage("Hardware-Backed Keystore Vault: Active (AES-256-GCM)\n\n" +
                        "Account credentials, proxy secrets, and TDLib database encryption keys are strictly isolated per account.")
                .setPositiveButton("Understood", null)
                .show();
    }

    private void confirmLogout() {
        AccountManager manager = NexGramApplication.getInstance().getAccountManager();
        AccountManager.AccountItem active = manager != null ? manager.getActiveAccount() : null;
        if (active == null) {
            finish();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Log Out?")
                .setMessage("Are you sure you want to log out from " + active.name + "? All authentication tokens will be securely wiped from the Android Keystore.")
                .setPositiveButton("Log Out", (dialog, which) -> {
                    TdLibManager tdManager = NexGramApplication.getInstance().getTdLibManager();
                    if (tdManager != null) {
                        TdLibAccountClient client = tdManager.getClient(active.id);
                        if (client != null) {
                            client.logOut(result -> {
                                client.deleteDatabaseFiles();
                            });
                        }
                        tdManager.closeClient(active.id);
                    }
                    manager.removeAccount(active.id);
                    AccountManager.AccountItem nextActive = manager.getActiveAccount();
                    if (nextActive != null) {
                        Toast.makeText(this, "Logged out from " + active.name + ". Switched to " + nextActive.name + ".", Toast.LENGTH_SHORT).show();
                        loadProfile();
                    } else {
                        Toast.makeText(this, "Logged out. Session wiped from Keystore.", Toast.LENGTH_SHORT).show();
                        loadProfile();
                        finish();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
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
