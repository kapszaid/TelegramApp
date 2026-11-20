package com.nexgram.messenger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.nexgram.messenger.backup.AccountBackupManager;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit Test Suite for NexGram AES-256-GCM Backup Cryptography
 * Tests encryption roundtrip, incorrect passwords, corrupted archives, and parameter checks.
 */
public class BackupCryptoTest {

    private AccountBackupManager manager;

    @Before
    public void setUp() {
        manager = new AccountBackupManager();
    }

    private AccountBackupManager.BackupPayload createSamplePayload() {
        AccountBackupManager.BackupPayload payload = new AccountBackupManager.BackupPayload();
        payload.version = AccountBackupManager.CURRENT_ARCHIVE_VERSION;
        payload.accountId = "987654321";
        payload.accountName = "Authorized Tester";
        payload.phone = "+12025550143";
        payload.username = "authtester";
        payload.encryptedAuthToken = "aabbccddeeff001122334455";
        payload.proxySettingsJson = "{\"host\":\"proxy.example.com\",\"port\":1080}";
        payload.exportedAt = System.currentTimeMillis();
        return payload;
    }

    @Test
    public void testSuccessfulBackupAndRestoreRoundTrip() throws Exception {
        AccountBackupManager.BackupPayload original = createSamplePayload();
        char[] password = "SecureMasterPassword#2026".toCharArray();

        String encryptedArchive = manager.createEncryptedBackup(original, password);
        assertNotNull(encryptedArchive);
        assertTrue(encryptedArchive.startsWith(AccountBackupManager.CURRENT_ARCHIVE_VERSION + ":"));

        AccountBackupManager.BackupPayload restored = manager.restoreEncryptedBackup(encryptedArchive, password);
        assertNotNull(restored);
        assertEquals(original.accountId, restored.accountId);
        assertEquals(original.phone, restored.phone);
        assertEquals(original.username, restored.username);
        assertEquals(original.accountName, restored.accountName);
        assertEquals(original.encryptedAuthToken, restored.encryptedAuthToken);
    }

    @Test(expected = AccountBackupManager.BackupAuthenticationException.class)
    public void testIncorrectPasswordRejection() throws Exception {
        AccountBackupManager.BackupPayload original = createSamplePayload();
        char[] correctPassword = "CorrectPassword123".toCharArray();
        char[] wrongPassword = "WrongPassword456".toCharArray();

        String encryptedArchive = manager.createEncryptedBackup(original, correctPassword);
        assertNotNull(encryptedArchive);

        // Attempt decryption with wrong password - MUST throw AuthenticationFailedException
        manager.restoreEncryptedBackup(encryptedArchive, wrongPassword);
    }

    @Test(expected = AccountBackupManager.BackupAuthenticationException.class)
    public void testTamperedCiphertextRejection() throws Exception {
        AccountBackupManager.BackupPayload original = createSamplePayload();
        char[] password = "Password123456".toCharArray();

        String encryptedArchive = manager.createEncryptedBackup(original, password);
        String[] parts = encryptedArchive.split(":");

        // Flip bytes in ciphertext - GCM authentication tag must detect tampering and throw BackupAuthenticationException
        String tamperedCiphertext = parts[3].substring(0, parts[3].length() - 4) + "AAAA";
        String tamperedArchive = parts[0] + ":" + parts[1] + ":" + parts[2] + ":" + tamperedCiphertext;

        manager.restoreEncryptedBackup(tamperedArchive, password);
    }

    @Test(expected = AccountBackupManager.CorruptedArchiveException.class)
    public void testMalformedArchiveHeaderRejection() throws Exception {
        char[] password = "Password123456".toCharArray();
        manager.restoreEncryptedBackup("invalid:format:data", password);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testShortPasswordRejection() throws Exception {
        AccountBackupManager.BackupPayload original = createSamplePayload();
        char[] shortPassword = "short".toCharArray();
        manager.createEncryptedBackup(original, shortPassword);
    }

    @Test(expected = AccountBackupManager.CorruptedArchiveException.class)
    public void testMissingMandatoryFieldsValidation() throws Exception {
        AccountBackupManager.BackupPayload invalid = new AccountBackupManager.BackupPayload();
        invalid.accountId = null; // Missing mandatory ID
        char[] password = "ValidPassword123".toCharArray();
        manager.createEncryptedBackup(invalid, password);
    }
}
