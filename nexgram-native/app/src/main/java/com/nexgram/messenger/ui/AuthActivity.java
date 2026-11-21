package com.nexgram.messenger.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.cardview.widget.CardView;
import com.google.android.material.textfield.TextInputLayout;
import com.nexgram.messenger.NexGramApplication;
import com.nexgram.messenger.R;
import com.nexgram.messenger.auth.AccountManager;
import com.nexgram.messenger.proxy.ProxyController;
import com.nexgram.messenger.tdlib.TdLibAccountClient;
import com.nexgram.messenger.tdlib.TdLibManager;
import com.nexgram.messenger.ui.helpers.ProxyDialogHelper;
import java.util.ArrayList;
import java.util.List;
import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

/**
 * NexGram Authentication Activity
 *
 * Implements genuine Telegram authentication via native TDLib 1.8.70.
 * Flow: Phone Number -> OTP Verification Code -> Two-Step Verification (if set) -> Authorized
 *
 * SECURITY INVARIANTS:
 * 1. Zero fake or simulated authentication: communicates strictly with official Telegram MTProto servers.
 * 2. Never stores or logs verification codes (OTP) or 2FA passwords in plaintext or SharedPreferences.
 * 3. Never prints private auth tokens or credentials to Android logcat.
 * 4. TDLib account database is encrypted with hardware-backed Android Keystore MasterKey (AES-256-GCM).
 */
public class AuthActivity extends AppCompatActivity {
    private static final String TAG = "AuthActivity";
    public static final String DEFAULT_ACCOUNT_ID = "account_main";
    public static final String ACCOUNT_ID = DEFAULT_ACCOUNT_ID;
    private String accountId = DEFAULT_ACCOUNT_ID;
    private boolean isAuthenticationSuccessful = false;

    private static class CountryItem {
        final String name;
        final String code;

        CountryItem(String name, String code) {
            this.name = name;
            this.code = code;
        }

        @Override
        public String toString() {
            return name + " (" + code + ")";
        }
    }

    private Toolbar toolbarAuth;
    private ProgressBar progressBarAuth;
    private CardView cardAuthError;
    private TextView tvAuthErrorMessage;
    private ImageView btnDismissError;

    // Steps
    private View layoutStepPhone, layoutStepOtp, layoutStepPassword, layoutStepRegistration;

    // Step 1: Phone
    private Spinner spCountryPicker;
    private EditText etCountryCode, etPhoneNumber;
    private Button btnPhoneContinue;

    // Step 2: OTP
    private TextView tvOtpSubtitle, tvOtpPhoneDisplay, btnOtpWrongNumber;
    private EditText etOtpCode;
    private Button btnOtpVerify, btnOtpResend;

    // Step 3: 2FA Password
    private TextView tvPasswordHint;
    private EditText etPassword;
    private Button btnPasswordSubmit;

    // Step 4: Name Registration
    private EditText etFirstName, etLastName;
    private Button btnRegisterSubmit;

    private TdLibAccountClient client;
    private String currentPhoneNumber = "";
    private final List<CountryItem> countryList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auth);

        String reqId = getIntent().getStringExtra("account_id");
        AccountManager accMgr = NexGramApplication.getInstance().getAccountManager();
        if (reqId != null && !reqId.trim().isEmpty()) {
            this.accountId = reqId.trim();
        } else if (accMgr != null && accMgr.getActiveAccount() != null) {
            this.accountId = accMgr.generateNewAccountId();
        } else {
            this.accountId = DEFAULT_ACCOUNT_ID;
        }

        initViews();
        setupCountryPicker();
        initTdLibClient();
    }

    private void initViews() {
        toolbarAuth = findViewById(R.id.toolbarAuth);
        setSupportActionBar(toolbarAuth);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.auth_title);
        }

        progressBarAuth = findViewById(R.id.progressBarAuth);
        cardAuthError = findViewById(R.id.cardAuthError);
        tvAuthErrorMessage = findViewById(R.id.tvAuthErrorMessage);
        btnDismissError = findViewById(R.id.btnDismissError);

        btnDismissError.setOnClickListener(v -> cardAuthError.setVisibility(View.GONE));

        layoutStepPhone = findViewById(R.id.layoutStepPhone);
        layoutStepOtp = findViewById(R.id.layoutStepOtp);
        layoutStepPassword = findViewById(R.id.layoutStepPassword);
        layoutStepRegistration = findViewById(R.id.layoutStepRegistration);

        // Step 1
        spCountryPicker = findViewById(R.id.spCountryPicker);
        etCountryCode = findViewById(R.id.etCountryCode);
        etPhoneNumber = findViewById(R.id.etPhoneNumber);
        btnPhoneContinue = findViewById(R.id.btnPhoneContinue);
        btnPhoneContinue.setOnClickListener(v -> submitPhoneNumber());

        // Step 2
        tvOtpSubtitle = findViewById(R.id.tvOtpSubtitle);
        tvOtpPhoneDisplay = findViewById(R.id.tvOtpPhoneDisplay);
        btnOtpWrongNumber = findViewById(R.id.btnOtpWrongNumber);
        etOtpCode = findViewById(R.id.etOtpCode);
        btnOtpVerify = findViewById(R.id.btnOtpVerify);
        btnOtpResend = findViewById(R.id.btnOtpResend);

        btnOtpWrongNumber.setOnClickListener(v -> showStep(layoutStepPhone));
        btnOtpVerify.setOnClickListener(v -> submitOtpCode());
        btnOtpResend.setOnClickListener(v -> resendOtpCode());

        // Step 3
        tvPasswordHint = findViewById(R.id.tvPasswordHint);
        etPassword = findViewById(R.id.etPassword);
        btnPasswordSubmit = findViewById(R.id.btnPasswordSubmit);
        btnPasswordSubmit.setOnClickListener(v -> submitPassword());

        // Step 4
        etFirstName = findViewById(R.id.etFirstName);
        etLastName = findViewById(R.id.etLastName);
        btnRegisterSubmit = findViewById(R.id.btnRegisterSubmit);
        btnRegisterSubmit.setOnClickListener(v -> submitRegistration());
    }

    private void setupCountryPicker() {
        countryList.add(new CountryItem("United States / Canada", "+1"));
        countryList.add(new CountryItem("United Kingdom", "+44"));
        countryList.add(new CountryItem("India", "+91"));
        countryList.add(new CountryItem("Germany", "+49"));
        countryList.add(new CountryItem("France", "+33"));
        countryList.add(new CountryItem("Italy", "+39"));
        countryList.add(new CountryItem("Spain", "+34"));
        countryList.add(new CountryItem("Russia", "+7"));
        countryList.add(new CountryItem("Brazil", "+55"));
        countryList.add(new CountryItem("Australia", "+61"));
        countryList.add(new CountryItem("Japan", "+81"));
        countryList.add(new CountryItem("China", "+86"));
        countryList.add(new CountryItem("Turkey", "+90"));
        countryList.add(new CountryItem("United Arab Emirates", "+971"));
        countryList.add(new CountryItem("Saudi Arabia", "+966"));
        countryList.add(new CountryItem("Pakistan", "+92"));
        countryList.add(new CountryItem("Bangladesh", "+880"));
        countryList.add(new CountryItem("Indonesia", "+62"));
        countryList.add(new CountryItem("Nigeria", "+234"));
        countryList.add(new CountryItem("South Africa", "+27"));
        countryList.add(new CountryItem("Egypt", "+20"));
        countryList.add(new CountryItem("Mexico", "+52"));
        countryList.add(new CountryItem("Custom / Other", "+"));

        ArrayAdapter<CountryItem> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, countryList);
        spCountryPicker.setAdapter(adapter);

        spCountryPicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                CountryItem item = countryList.get(position);
                if (!item.code.equals("+")) {
                    etCountryCode.setText(item.code);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void initTdLibClient() {
        TdLibManager tdManager = NexGramApplication.getInstance().getTdLibManager();
        if (tdManager == null) {
            showError("Native TDLib engine is unavailable. Please check Keystore vault initialization.");
            return;
        }

        setLoading(true);
        this.client = tdManager.getOrCreateClient(this.accountId);

        this.client.addStateListener(new TdLibAccountClient.StateListener() {
            @Override
            public void onAuthorizationStateChanged(TdApi.AuthorizationState newState) {
                runOnUiThread(() -> handleAuthStateChanged(newState));
            }

            @Override
            public void onClientError(int errorCode, String errorMessage) {
                runOnUiThread(() -> {
                    setLoading(false);
                    showError("TDLib Engine Error [" + errorCode + "]: " + errorMessage);
                });
            }
        });

        // If client already has an active authorization state, apply it immediately
        TdApi.AuthorizationState initial = client.getCurrentAuthState();
        if (initial != null) {
            handleAuthStateChanged(initial);
        }
    }

    private void handleAuthStateChanged(TdApi.AuthorizationState state) {
        setLoading(false);
        if (state == null) return;

        Log.i(TAG, "Auth state transitioned to: " + state.getClass().getSimpleName());

        if (state instanceof TdApi.AuthorizationStateWaitTdlibParameters) {
            setLoading(true);
        } else if (state instanceof TdApi.AuthorizationStateWaitPhoneNumber) {
            showStep(layoutStepPhone);
        } else if (state instanceof TdApi.AuthorizationStateWaitCode) {
            TdApi.AuthorizationStateWaitCode waitCode = (TdApi.AuthorizationStateWaitCode) state;
            tvOtpPhoneDisplay.setText(currentPhoneNumber);
            showStep(layoutStepOtp);
            etOtpCode.requestFocus();
        } else if (state instanceof TdApi.AuthorizationStateWaitPassword) {
            TdApi.AuthorizationStateWaitPassword waitPassword = (TdApi.AuthorizationStateWaitPassword) state;
            if (waitPassword.passwordHint != null && !waitPassword.passwordHint.trim().isEmpty()) {
                tvPasswordHint.setVisibility(View.VISIBLE);
                tvPasswordHint.setText("Password Hint: " + waitPassword.passwordHint);
            } else {
                tvPasswordHint.setVisibility(View.GONE);
            }
            showStep(layoutStepPassword);
            etPassword.requestFocus();
        } else if (state instanceof TdApi.AuthorizationStateWaitRegistration) {
            showStep(layoutStepRegistration);
            etFirstName.requestFocus();
        } else if (state instanceof TdApi.AuthorizationStateWaitEmailAddress) {
            showError("Telegram requested email authorization. Please verify via standard Telegram client.");
        } else if (state instanceof TdApi.AuthorizationStateWaitEmailCode) {
            showError("Telegram requested email verification code.");
        } else if (state instanceof TdApi.AuthorizationStateWaitOtherDeviceConfirmation) {
            showError("Please confirm login request on your other active Telegram device.");
        } else if (state instanceof TdApi.AuthorizationStateReady) {
            onAuthenticationSuccess();
        } else if (state instanceof TdApi.AuthorizationStateClosed) {
            showError("TDLib client closed unexpectedly.");
        }
    }

    private void submitPhoneNumber() {
        String code = etCountryCode.getText().toString().trim();
        String number = etPhoneNumber.getText().toString().trim();

        if (number.isEmpty()) {
            showError("Please enter your phone number.");
            etPhoneNumber.requestFocus();
            return;
        }

        if (!code.startsWith("+")) {
            code = "+" + code;
        }

        this.currentPhoneNumber = code + number;
        hideError();
        setLoading(true);

        client.setAuthenticationPhoneNumber(this.currentPhoneNumber, result -> {
            runOnUiThread(() -> {
                setLoading(false);
                if (result instanceof TdApi.Error) {
                    TdApi.Error error = (TdApi.Error) result;
                    showError(mapErrorMessage(error.code, error.message));
                }
            });
        });
    }

    private void submitOtpCode() {
        String code = etOtpCode.getText().toString().trim();
        if (code.isEmpty()) {
            showError("Please enter the verification code.");
            etOtpCode.requestFocus();
            return;
        }

        hideError();
        setLoading(true);

        client.checkAuthenticationCode(code, result -> {
            runOnUiThread(() -> {
                setLoading(false);
                if (result instanceof TdApi.Error) {
                    TdApi.Error error = (TdApi.Error) result;
                    showError(mapErrorMessage(error.code, error.message));
                }
            });
        });
    }

    private void resendOtpCode() {
        hideError();
        setLoading(true);

        client.resendAuthenticationCode(result -> {
            runOnUiThread(() -> {
                setLoading(false);
                if (result instanceof TdApi.Error) {
                    TdApi.Error error = (TdApi.Error) result;
                    showError(mapErrorMessage(error.code, error.message));
                } else {
                    Toast.makeText(this, "Verification code resent.", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void submitPassword() {
        String password = etPassword.getText().toString();
        if (password.isEmpty()) {
            showError("Please enter your Two-Step Verification password.");
            etPassword.requestFocus();
            return;
        }

        hideError();
        setLoading(true);

        client.checkAuthenticationPassword(password, result -> {
            runOnUiThread(() -> {
                setLoading(false);
                if (result instanceof TdApi.Error) {
                    TdApi.Error error = (TdApi.Error) result;
                    showError(mapErrorMessage(error.code, error.message));
                }
            });
        });
    }

    private void submitRegistration() {
        String first = etFirstName.getText().toString().trim();
        String last = etLastName.getText().toString().trim();

        if (first.isEmpty()) {
            showError("Please enter your first name.");
            etFirstName.requestFocus();
            return;
        }

        hideError();
        setLoading(true);

        client.registerUser(first, last, result -> {
            runOnUiThread(() -> {
                setLoading(false);
                if (result instanceof TdApi.Error) {
                    TdApi.Error error = (TdApi.Error) result;
                    showError(mapErrorMessage(error.code, error.message));
                }
            });
        });
    }

    private void onAuthenticationSuccess() {
        setLoading(true);
        isAuthenticationSuccessful = true;
        client.getMe(result -> {
            runOnUiThread(() -> {
                setLoading(false);
                if (result instanceof TdApi.User) {
                    TdApi.User user = (TdApi.User) result;
                    String fullName = (user.firstName + (user.lastName != null && !user.lastName.isEmpty() ? " " + user.lastName : "")).trim();
                    String phone = user.phoneNumber != null ? user.phoneNumber : currentPhoneNumber;
                    String username = (user.usernames != null && user.usernames.activeUsernames != null && user.usernames.activeUsernames.length > 0)
                            ? user.usernames.activeUsernames[0] : "";

                    AccountManager manager = NexGramApplication.getInstance().getAccountManager();
                    if (manager != null) {
                        // Check if this account is already registered under another ID
                        AccountManager.AccountItem existing = manager.getAccountByUserId(user.id);
                        if (existing != null && !existing.id.equals(this.accountId)) {
                            Toast.makeText(this, "Account already added (" + existing.name + "). Switching...", Toast.LENGTH_SHORT).show();
                            manager.switchAccount(existing.id);
                            TdLibManager tdManager = NexGramApplication.getInstance().getTdLibManager();
                            if (tdManager != null) {
                                tdManager.closeClient(this.accountId);
                            }
                            navigateToMain();
                            return;
                        }

                        AccountManager.AccountItem item = new AccountManager.AccountItem(
                                this.accountId,
                                fullName.isEmpty() ? "Telegram User" : fullName,
                                phone.startsWith("+") ? phone : "+" + phone,
                                username,
                                user.id,
                                null
                        );
                        manager.addAccount(item, "tdlib_session_" + this.accountId);
                        manager.switchAccount(this.accountId);
                    }

                    Toast.makeText(this, "Welcome, " + (fullName.isEmpty() ? "Telegram User" : fullName) + "!", Toast.LENGTH_LONG).show();
                    navigateToMain();
                } else {
                    navigateToMain();
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!isAuthenticationSuccessful) {
            AccountManager manager = NexGramApplication.getInstance().getAccountManager();
            if (manager != null && manager.getAccount(this.accountId) == null && !DEFAULT_ACCOUNT_ID.equals(this.accountId)) {
                TdLibManager tdManager = NexGramApplication.getInstance().getTdLibManager();
                if (tdManager != null) {
                    tdManager.closeClient(this.accountId);
                }
            }
        }
    }

    private void navigateToMain() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private void showStep(View activeStep) {
        layoutStepPhone.setVisibility(activeStep == layoutStepPhone ? View.VISIBLE : View.GONE);
        layoutStepOtp.setVisibility(activeStep == layoutStepOtp ? View.VISIBLE : View.GONE);
        layoutStepPassword.setVisibility(activeStep == layoutStepPassword ? View.VISIBLE : View.GONE);
        layoutStepRegistration.setVisibility(activeStep == layoutStepRegistration ? View.VISIBLE : View.GONE);
    }

    private void setLoading(boolean loading) {
        progressBarAuth.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnPhoneContinue.setEnabled(!loading);
        btnOtpVerify.setEnabled(!loading);
        btnOtpResend.setEnabled(!loading);
        btnPasswordSubmit.setEnabled(!loading);
        btnRegisterSubmit.setEnabled(!loading);
    }

    private void showError(String message) {
        tvAuthErrorMessage.setText(message);
        cardAuthError.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        cardAuthError.setVisibility(View.GONE);
    }

    private String mapErrorMessage(int code, String message) {
        if (message == null) return "An unexpected error occurred (" + code + ")";
        if (message.contains("PHONE_NUMBER_INVALID")) {
            return "Invalid phone number. Please check country code and number.";
        } else if (message.contains("PHONE_CODE_INVALID")) {
            return "Invalid verification code. Please check and try again.";
        } else if (message.contains("PHONE_CODE_EXPIRED")) {
            return "The verification code has expired. Please request a new code.";
        } else if (message.contains("PASSWORD_HASH_INVALID")) {
            return "Incorrect Two-Step Verification password. Please try again.";
        } else if (message.contains("FLOOD_WAIT")) {
            return "Too many attempts. Telegram requested cooldown. Please wait before retrying.";
        } else if (message.contains("FIRSTNAME_INVALID")) {
            return "Please enter a valid first name.";
        }
        return "Telegram error: " + message;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 101, 0, "Proxy")
                .setIcon(R.drawable.ic_shield_proxy)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        } else if (item.getItemId() == 101) {
            showProxyConfigurationDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showProxyConfigurationDialog() {
        ProxyDialogHelper.showProxyDialog(this, this.accountId, () -> {
            Log.i(TAG, "Proxy updated for account: " + this.accountId);
        });
    }
}
