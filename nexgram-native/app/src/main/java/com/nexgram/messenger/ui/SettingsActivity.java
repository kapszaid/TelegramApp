package com.nexgram.messenger.ui;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import com.google.android.material.textfield.TextInputLayout;
import com.nexgram.messenger.NexGramApplication;
import com.nexgram.messenger.R;
import com.nexgram.messenger.auth.AccountManager;
import com.nexgram.messenger.proxy.ProxyController;
import com.nexgram.messenger.tdlib.TdLibAccountClient;
import com.nexgram.messenger.tdlib.TdLibManager;
import com.nexgram.messenger.ui.helpers.ProxyDialogHelper;
import org.drinkless.tdlib.TdApi;

/**
 * Native Settings Activity
 *
 * Implements Milestone 2.5 — Account-Specific Proxy Manager:
 * - Independent proxy configurations for every Telegram account
 * - Direct, SOCKS5, and MTProto routing support
 * - Dynamic proxy switching after login WITHOUT logout, session loss, or OTP
 * - Live proxy verification via native TDLib PingProxy
 * - Encrypted credential isolation in Android Keystore via SecureSessionVault
 */
public class SettingsActivity extends AppCompatActivity {
    private static final String TAG = "SettingsActivity";

    private TextView tvSettingsUser, tvSettingsPhone, tvProxyStatus;
    private Button btnLogout;
    private CardView cardAccountProfile, cardProxy, cardBackup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.title_settings);
        }

        tvSettingsUser = findViewById(R.id.tvSettingsUser);
        tvSettingsPhone = findViewById(R.id.tvSettingsPhone);
        tvProxyStatus = findViewById(R.id.tvProxyStatus);
        cardAccountProfile = findViewById(R.id.cardAccountProfile);
        cardProxy = findViewById(R.id.cardProxySettings);
        cardBackup = findViewById(R.id.cardBackup);
        btnLogout = findViewById(R.id.btnSettingsLogout);

        loadProfile();

        if (cardAccountProfile != null) {
            cardAccountProfile.setOnClickListener(v -> {
                AccountManager manager = NexGramApplication.getInstance().getAccountManager();
                if (manager == null || manager.getActiveAccount() == null) {
                    startActivity(new Intent(SettingsActivity.this, AuthActivity.class));
                }
            });
        }

        cardProxy.setOnClickListener(v -> showProxyConfigurationDialog());
        cardBackup.setOnClickListener(v -> showBackupInfoDialog());
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
            tvSettingsUser.setText(active.name);
            tvSettingsPhone.setText(active.phone);
            btnLogout.setVisibility(View.VISIBLE);
        } else {
            tvSettingsUser.setText("No Active User");
            tvSettingsPhone.setText("Tap to log in with Telegram");
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

    private void showBackupInfoDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.title_backup)
                .setMessage("Hardware-Backed Keystore Vault: Active (AES-256-GCM)\n\n" +
                             "Account credentials, proxy secrets, and TDLib database encryption keys are isolated per account.")
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
                                android.util.Log.i("SettingsActivity", "TDLib logOut finished: " + result);
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
