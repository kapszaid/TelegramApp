package com.nexgram.messenger.ui;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.nexgram.messenger.NexGramApplication;
import com.nexgram.messenger.R;
import com.nexgram.messenger.auth.AccountManager;
import com.nexgram.messenger.tdlib.TdLibAccountClient;
import com.nexgram.messenger.tdlib.TdLibManager;
import com.nexgram.messenger.ui.adapter.DeviceSessionAdapter;

import org.drinkless.tdlib.TdApi;

import java.util.ArrayList;
import java.util.List;

public class DevicesActivity extends AppCompatActivity {
    private static final String TAG = "DevicesActivity";

    private String accountId;
    private TdLibAccountClient client;

    private Toolbar toolbar;
    private TextView tvCurrentTitle;
    private TextView tvCurrentSubtitle;
    private TextView tvCurrentLocation;

    private Button btnTerminateAllOther;
    private RecyclerView rvSessions;
    private DeviceSessionAdapter adapter;
    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_devices);

        accountId = getIntent().getStringExtra("account_id");
        resolveAccountClient();

        initViews();
        loadSessions();
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
        toolbar = findViewById(R.id.toolbarDevices);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        tvCurrentTitle = findViewById(R.id.tvCurrentDeviceTitle);
        tvCurrentSubtitle = findViewById(R.id.tvCurrentDeviceSubtitle);
        tvCurrentLocation = findViewById(R.id.tvCurrentDeviceLocation);

        btnTerminateAllOther = findViewById(R.id.btnTerminateAllOther);
        rvSessions = findViewById(R.id.rvActiveSessions);
        progressBar = findViewById(R.id.pbDevices);

        rvSessions.setLayoutManager(new LinearLayoutManager(this));
        adapter = new DeviceSessionAdapter(this, this::confirmTerminateSession);
        rvSessions.setAdapter(adapter);

        btnTerminateAllOther.setOnClickListener(v -> confirmTerminateAllOther());
    }

    private void loadSessions() {
        if (client == null) return;
        progressBar.setVisibility(View.VISIBLE);

        client.getActiveSessions(res -> runOnUiThread(() -> {
            progressBar.setVisibility(View.GONE);
            if (res instanceof TdApi.Sessions) {
                TdApi.Session[] all = ((TdApi.Sessions) res).sessions;
                if (all != null) {
                    List<TdApi.Session> otherSessions = new ArrayList<>();
                    for (TdApi.Session s : all) {
                        if (s.isCurrent) {
                            bindCurrentSession(s);
                        } else {
                            otherSessions.add(s);
                        }
                    }
                    adapter.setSessions(otherSessions);
                    btnTerminateAllOther.setVisibility(otherSessions.isEmpty() ? View.GONE : View.VISIBLE);
                }
            } else if (res instanceof TdApi.Error) {
                Toast.makeText(this, ((TdApi.Error) res).message, Toast.LENGTH_LONG).show();
            }
        }));
    }

    private void bindCurrentSession(TdApi.Session s) {
        String model = s.deviceModel != null && !s.deviceModel.isEmpty() ? s.deviceModel : "This Device";
        tvCurrentTitle.setText(model);

        String app = (s.applicationName != null ? s.applicationName : "NexGram") +
                (s.applicationVersion != null ? " " + s.applicationVersion : " 1.0.0") +
                (s.platform != null ? " • " + s.platform : "") +
                (s.systemVersion != null ? " " + s.systemVersion : "");
        tvCurrentSubtitle.setText(app);

        String loc = (s.location != null && !s.location.isEmpty() ? s.location : "Local Network") +
                (s.ipAddress != null && !s.ipAddress.isEmpty() ? " • " + s.ipAddress : "");
        tvCurrentLocation.setText(loc);
    }

    private void confirmTerminateSession(TdApi.Session session) {
        String model = session.deviceModel != null ? session.deviceModel : "this device";
        new AlertDialog.Builder(this)
                .setTitle("Terminate Session?")
                .setMessage("Are you sure you want to terminate session on " + model + "?")
                .setPositiveButton("Terminate", (dialog, which) -> {
                    progressBar.setVisibility(View.VISIBLE);
                    client.terminateSession(session.id, res -> runOnUiThread(() -> {
                        progressBar.setVisibility(View.GONE);
                        if (res instanceof TdApi.Ok) {
                            Toast.makeText(this, "Session terminated", Toast.LENGTH_SHORT).show();
                            loadSessions();
                        } else if (res instanceof TdApi.Error) {
                            Toast.makeText(this, ((TdApi.Error) res).message, Toast.LENGTH_LONG).show();
                        }
                    }));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmTerminateAllOther() {
        new AlertDialog.Builder(this)
                .setTitle("Terminate All Other Sessions?")
                .setMessage("Are you sure you want to log out all other devices? All other sessions will be terminated.")
                .setPositiveButton("Terminate All", (dialog, which) -> {
                    progressBar.setVisibility(View.VISIBLE);
                    client.terminateAllOtherSessions(res -> runOnUiThread(() -> {
                        progressBar.setVisibility(View.GONE);
                        if (res instanceof TdApi.Ok) {
                            Toast.makeText(this, "All other sessions terminated", Toast.LENGTH_SHORT).show();
                            loadSessions();
                        } else if (res instanceof TdApi.Error) {
                            Toast.makeText(this, ((TdApi.Error) res).message, Toast.LENGTH_LONG).show();
                        }
                    }));
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
