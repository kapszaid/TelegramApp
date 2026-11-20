package com.nexgram.messenger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import com.nexgram.messenger.backup.AccountBackupManager;
import org.junit.Test;

/**
 * Unit Test for NexGram Native AES-256-GCM Encrypted Backup & Restore
 */
public class BackupCryptoTest {

    @Test
    public void testEncryptedBackupAndRestore() throws Exception {
        AccountBackupManager manager = new AccountBackupManager();
        AccountBackupManager.BackupPayload payload = new AccountBackupManager.BackupPayload();
        payload.accountId = "123456789";
        payload.accountName = "Test User";
        payload.phone = "+12025550199";
        payload.username = "testuser";
        payload.encryptedAuthToken = "0123456789abcdef";

        String password = "StrongMasterPassword#2026";

        // Encrypt
        String encryptedArchive = manager.createEncryptedBackup(payload, password);
        assertNotNull(encryptedArchive);

        // Decrypt
        AccountBackupManager.BackupPayload restored = manager.restoreEncryptedBackup(encryptedArchive, password);
        assertNotNull(restored);
        assertEquals(payload.accountId, restored.accountId);
        assertEquals(payload.accountName, restored.accountName);
        assertEquals(payload.phone, restored.phone);
        assertEquals(payload.username, restored.username);
    }
}
