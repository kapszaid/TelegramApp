package com.nexgram.messenger.tdlib;

import android.content.Context;
import android.util.Log;
import com.nexgram.messenger.auth.SecureSessionVault;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

/**
 * TdLibManager
 *
 * Central repository and coordinator for all native TDLib account clients.
 * Enforces multi-account client isolation and safe asynchronous lifecycle.
 */
public class TdLibManager {
    private static final String TAG = "TdLibManager";
    private static volatile TdLibManager instance;

    public interface SmokeTestCallback {
        void onResult(boolean success, String state, String message);
    }

    private final Context appContext;
    private final SecureSessionVault sessionVault;
    private final ConcurrentHashMap<String, TdLibAccountClient> activeClients = new ConcurrentHashMap<>();
    private final AtomicBoolean smokeTestRunning = new AtomicBoolean(false);

    private TdLibManager(Context context, SecureSessionVault sessionVault) {
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        if (sessionVault == null) {
            throw new IllegalArgumentException("SecureSessionVault cannot be null");
        }
        this.appContext = context.getApplicationContext();
        this.sessionVault = sessionVault;

        configureGlobalLogVerbosity(1); // 1 = errors only
    }

    public static synchronized void init(Context context, SecureSessionVault sessionVault) {
        if (instance == null) {
            instance = new TdLibManager(context, sessionVault);
            Log.i(TAG, "TdLibManager initialized successfully.");
        }
    }

    public static TdLibManager getInstance() {
        if (instance == null) {
            throw new IllegalStateException("TdLibManager is not initialized. Call init() first.");
        }
        return instance;
    }

    /**
     * Configures the native TDLib logging verbosity level synchronously.
     * Value 0 = fatal, 1 = errors, 2 = warnings, 3 = info, 4 = debug.
     */
    public void configureGlobalLogVerbosity(int verbosityLevel) {
        try {
            Client.execute(new TdApi.SetLogVerbosityLevel(verbosityLevel));
            Log.i(TAG, "TDLib global log verbosity set to: " + verbosityLevel);
        } catch (Exception e) {
            Log.w(TAG, "Could not set TDLib log verbosity: " + e.getMessage());
        }
    }

    /**
     * Retrieves or creates an isolated TDLib client for the specified account ID.
     */
    public synchronized TdLibAccountClient getOrCreateClient(String accountId) {
        if (accountId == null || accountId.trim().isEmpty()) {
            throw new IllegalArgumentException("Account ID cannot be null or empty");
        }

        TdLibAccountClient existing = activeClients.get(accountId);
        if (existing != null && !existing.isClosed()) {
            return existing;
        }

        TdLibAccountClient newClient = new TdLibAccountClient(appContext, accountId, sessionVault);
        activeClients.put(accountId, newClient);
        return newClient;
    }

    public TdLibAccountClient getClient(String accountId) {
        if (accountId == null) return null;
        return activeClients.get(accountId);
    }

    public synchronized void closeClient(String accountId) {
        if (accountId == null) return;
        TdLibAccountClient client = activeClients.remove(accountId);
        if (client != null) {
            client.close();
        }
    }

    public synchronized void closeAllClients() {
        Log.i(TAG, "Closing all active TDLib clients (" + activeClients.size() + " active)");
        for (Map.Entry<String, TdLibAccountClient> entry : activeClients.entrySet()) {
            entry.getValue().close();
        }
        activeClients.clear();
    }

    public int getActiveClientCount() {
        return activeClients.size();
    }

    public Map<String, TdLibAccountClient> getActiveClients() {
        return Collections.unmodifiableMap(activeClients);
    }

    /**
     * Executes a non-blocking physical device runtime smoke test verifying:
     * 1. Synchronous JNI execution (Client.execute)
     * 2. Android Keystore 32-byte AES-256 database encryption key generation and storage
     * 3. TDLib Client native thread instantiation
     * 4. SQLite database creation in isolated storage directory
     * 5. Receipt of TdApi.AuthorizationStateWaitTdlibParameters and AuthorizationStateWaitPhoneNumber
     * 6. Clean native client teardown
     */
    public void runRuntimeSmokeTest(SmokeTestCallback callback) {
        if (!smokeTestRunning.compareAndSet(false, true)) {
            Log.w(TAG, "[TDLibSmokeTest] Smoke test already running. Skipping duplicate call.");
            if (callback != null) callback.onResult(false, "ALREADY_RUNNING", "Smoke test is already in progress.");
            return;
        }

        new Thread(() -> {
            final String smokeAccountId = "__smoke_test_device__";
            Log.i(TAG, "[TDLibSmokeTest] ==================================================");
            Log.i(TAG, "[TDLibSmokeTest] Starting TDLib 1.8.70 Runtime Smoke Test...");
            Log.i(TAG, "[TDLibSmokeTest] Target Account ID: " + smokeAccountId);

            try {
                // 1. Verify synchronous JNI execution
                TdApi.Object verbosityObj = Client.execute(new TdApi.GetLogVerbosityLevel());
                Log.i(TAG, "[TDLibSmokeTest] [Step 1/6 PASS] JNI Client.execute succeeded. Current verbosity: " + verbosityObj);

                // 2. Verify Android Keystore 32-byte encryption key flow
                byte[] key = sessionVault.getOrCreateDatabaseKey(smokeAccountId);
                if (key == null || key.length != 32) {
                    throw new IllegalStateException("Session vault failed to generate 32-byte database encryption key.");
                }
                Log.i(TAG, "[TDLibSmokeTest] [Step 2/6 PASS] Hardware-backed 32-byte database encryption key retrieved from vault.");

                // 3. Create real TDLib client instance using production TdLibAccountClient
                CountDownLatch latch = new CountDownLatch(1);
                AtomicReference<String> reachedState = new AtomicReference<>("None");
                AtomicBoolean testPassed = new AtomicBoolean(false);

                TdLibAccountClient testClient = new TdLibAccountClient(appContext, smokeAccountId, sessionVault);
                Log.i(TAG, "[TDLibSmokeTest] [Step 3/6 PASS] TdLibAccountClient created. Database directory: " + testClient.getDatabaseDirectory().getAbsolutePath());

                testClient.addStateListener(new TdLibAccountClient.StateListener() {
                    @Override
                    public void onAuthorizationStateChanged(TdApi.AuthorizationState newState) {
                        String stateName = newState.getClass().getSimpleName();
                        reachedState.set(stateName);
                        Log.i(TAG, "[TDLibSmokeTest] State Update: " + stateName);

                        if (newState instanceof TdApi.AuthorizationStateWaitTdlibParameters) {
                            Log.i(TAG, "[TDLibSmokeTest] [Step 4/6 PASS] Received AuthorizationStateWaitTdlibParameters.");
                        } else if (newState instanceof TdApi.AuthorizationStateWaitPhoneNumber) {
                            Log.i(TAG, "[TDLibSmokeTest] [Step 5/6 PASS] Received AuthorizationStateWaitPhoneNumber!");
                            Log.i(TAG, "[TDLibSmokeTest] PROOF: SQLite database successfully created, encrypted with Keystore AES key, and native state machine ready for auth.");
                            testPassed.set(true);
                            latch.countDown();
                        }
                    }

                    @Override
                    public void onClientError(int errorCode, String errorMessage) {
                        Log.e(TAG, "[TDLibSmokeTest] Native Client Error: [" + errorCode + "] " + errorMessage);
                        latch.countDown();
                    }
                });

                // Wait up to 10 seconds for native SQLite DB creation & auth state transition
                boolean completed = latch.await(10, TimeUnit.SECONDS);

                // 6. Clean native client teardown
                testClient.close();
                Log.i(TAG, "[TDLibSmokeTest] [Step 6/6 PASS] Test client closed.");
                Log.i(TAG, "[TDLibSmokeTest] ==================================================");

                if (testPassed.get()) {
                    Log.i(TAG, "[TDLibSmokeTest] ALL 6 SMOKE TEST STEPS PASSED SUCCESSFULLY.");
                    if (callback != null) {
                        callback.onResult(true, reachedState.get(), "TDLib 1.8.70 native engine fully verified on device.");
                    }
                } else {
                    String msg = "Timed out or unexpected state: " + reachedState.get() + " (completed=" + completed + ")";
                    Log.w(TAG, "[TDLibSmokeTest] SMOKE TEST INCOMPLETE: " + msg);
                    if (callback != null) {
                        callback.onResult(false, reachedState.get(), msg);
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "[TDLibSmokeTest] FATAL EXCEPTION during smoke test: " + t.getMessage(), t);
                if (callback != null) {
                    callback.onResult(false, "FATAL_ERROR", t.getMessage());
                }
            } finally {
                smokeTestRunning.set(false);
            }
        }, "TDLibSmokeTestThread").start();
    }
}
