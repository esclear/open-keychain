/*
 * Copyright (C) 2012-2014 Dominik Schürmann <dominik@dominikschuermann.de>
 * Copyright (C) 2014 Vincent Breitmoser <v.breitmoser@mugenguild.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.sufficientlysecure.keychain.service;

import android.app.IntentService;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.helper.FileHelper;
import org.sufficientlysecure.keychain.helper.OtherHelper;
import org.sufficientlysecure.keychain.helper.Preferences;
import org.sufficientlysecure.keychain.keyimport.HkpKeyserver;
import org.sufficientlysecure.keychain.keyimport.ImportKeysListEntry;
import org.sufficientlysecure.keychain.keyimport.KeybaseKeyserver;
import org.sufficientlysecure.keychain.keyimport.Keyserver;
import org.sufficientlysecure.keychain.keyimport.ParcelableKeyRing;
import org.sufficientlysecure.keychain.pgp.CanonicalizedPublicKeyRing;
import org.sufficientlysecure.keychain.pgp.CanonicalizedSecretKey;
import org.sufficientlysecure.keychain.pgp.CanonicalizedSecretKeyRing;
import org.sufficientlysecure.keychain.pgp.PgpDecryptVerify;
import org.sufficientlysecure.keychain.service.results.DecryptVerifyResult;
import org.sufficientlysecure.keychain.pgp.PgpHelper;
import org.sufficientlysecure.keychain.pgp.PgpImportExport;
import org.sufficientlysecure.keychain.pgp.PgpKeyOperation;
import org.sufficientlysecure.keychain.pgp.PgpSignEncrypt;
import org.sufficientlysecure.keychain.pgp.Progressable;
import org.sufficientlysecure.keychain.pgp.UncachedKeyRing;
import org.sufficientlysecure.keychain.pgp.exception.PgpGeneralException;
import org.sufficientlysecure.keychain.pgp.exception.PgpGeneralMsgIdException;
import org.sufficientlysecure.keychain.provider.CachedPublicKeyRing;
import org.sufficientlysecure.keychain.provider.KeychainContract.KeyRingData;
import org.sufficientlysecure.keychain.provider.KeychainContract.KeyRings;
import org.sufficientlysecure.keychain.provider.KeychainDatabase;
import org.sufficientlysecure.keychain.provider.ProviderHelper;
import org.sufficientlysecure.keychain.service.results.OperationResultParcel;
import org.sufficientlysecure.keychain.service.results.OperationResultParcel.LogLevel;
import org.sufficientlysecure.keychain.service.results.OperationResultParcel.LogType;
import org.sufficientlysecure.keychain.service.results.OperationResultParcel.OperationLog;
import org.sufficientlysecure.keychain.service.results.ConsolidateResult;
import org.sufficientlysecure.keychain.service.results.EditKeyResult;
import org.sufficientlysecure.keychain.service.results.ImportKeyResult;
import org.sufficientlysecure.keychain.service.results.SaveKeyringResult;
import org.sufficientlysecure.keychain.util.ParcelableFileCache;
import org.sufficientlysecure.keychain.util.InputData;
import org.sufficientlysecure.keychain.util.Log;
import org.sufficientlysecure.keychain.util.ProgressScaler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * This Service contains all important long lasting operations for APG. It receives Intents with
 * data from the activities or other apps, queues these intents, executes them, and stops itself
 * after doing them.
 */
public class KeychainIntentService extends IntentService implements Progressable {

    /* extras that can be given by intent */
    public static final String EXTRA_MESSENGER = "messenger";
    public static final String EXTRA_DATA = "data";

    /* possible actions */
    public static final String ACTION_ENCRYPT_SIGN = Constants.INTENT_PREFIX + "ENCRYPT_SIGN";

    public static final String ACTION_DECRYPT_VERIFY = Constants.INTENT_PREFIX + "DECRYPT_VERIFY";

    public static final String ACTION_DECRYPT_METADATA = Constants.INTENT_PREFIX + "DECRYPT_METADATA";

    public static final String ACTION_EDIT_KEYRING = Constants.INTENT_PREFIX + "EDIT_KEYRING";

    public static final String ACTION_DELETE_FILE_SECURELY = Constants.INTENT_PREFIX
            + "DELETE_FILE_SECURELY";

    public static final String ACTION_IMPORT_KEYRING = Constants.INTENT_PREFIX + "IMPORT_KEYRING";
    public static final String ACTION_EXPORT_KEYRING = Constants.INTENT_PREFIX + "EXPORT_KEYRING";

    public static final String ACTION_UPLOAD_KEYRING = Constants.INTENT_PREFIX + "UPLOAD_KEYRING";
    public static final String ACTION_DOWNLOAD_AND_IMPORT_KEYS = Constants.INTENT_PREFIX + "QUERY_KEYRING";
    public static final String ACTION_IMPORT_KEYBASE_KEYS = Constants.INTENT_PREFIX + "DOWNLOAD_KEYBASE";

    public static final String ACTION_CERTIFY_KEYRING = Constants.INTENT_PREFIX + "SIGN_KEYRING";

    public static final String ACTION_DELETE = Constants.INTENT_PREFIX + "DELETE";

    public static final String ACTION_CONSOLIDATE = Constants.INTENT_PREFIX + "CONSOLIDATE";

    public static final String ACTION_CANCEL = Constants.INTENT_PREFIX + "CANCEL";

    /* keys for data bundle */

    // encrypt, decrypt, import export
    public static final String TARGET = "target";
    public static final String SOURCE = "source";
    // possible targets:
    public static final int IO_BYTES = 1;
    public static final int IO_URI = 2;
    public static final int IO_URIS = 3;

    public static final String SELECTED_URI = "selected_uri";

    // encrypt
    public static final String ENCRYPT_SIGNATURE_MASTER_ID = "secret_key_id";
    public static final String ENCRYPT_USE_ASCII_ARMOR = "use_ascii_armor";
    public static final String ENCRYPT_ENCRYPTION_KEYS_IDS = "encryption_keys_ids";
    public static final String ENCRYPT_COMPRESSION_ID = "compression_id";
    public static final String ENCRYPT_MESSAGE_BYTES = "message_bytes";
    public static final String ENCRYPT_INPUT_FILE = "input_file";
    public static final String ENCRYPT_INPUT_URI = "input_uri";
    public static final String ENCRYPT_INPUT_URIS = "input_uris";
    public static final String ENCRYPT_OUTPUT_FILE = "output_file";
    public static final String ENCRYPT_OUTPUT_URI = "output_uri";
    public static final String ENCRYPT_OUTPUT_URIS = "output_uris";
    public static final String ENCRYPT_SYMMETRIC_PASSPHRASE = "passphrase";

    // decrypt/verify
    public static final String DECRYPT_CIPHERTEXT_BYTES = "ciphertext_bytes";
    public static final String DECRYPT_PASSPHRASE = "passphrase";

    // save keyring
    public static final String EDIT_KEYRING_PARCEL = "save_parcel";
    public static final String EDIT_KEYRING_PASSPHRASE = "passphrase";

    // delete file securely
    public static final String DELETE_FILE = "deleteFile";

    // delete keyring(s)
    public static final String DELETE_KEY_LIST = "delete_list";
    public static final String DELETE_IS_SECRET = "delete_is_secret";

    // import key
    public static final String IMPORT_KEY_LIST = "import_key_list";
    public static final String IMPORT_KEY_FILE = "import_key_file";

    // export key
    public static final String EXPORT_OUTPUT_STREAM = "export_output_stream";
    public static final String EXPORT_FILENAME = "export_filename";
    public static final String EXPORT_URI = "export_uri";
    public static final String EXPORT_SECRET = "export_secret";
    public static final String EXPORT_ALL = "export_all";
    public static final String EXPORT_KEY_RING_MASTER_KEY_ID = "export_key_ring_id";

    // upload key
    public static final String UPLOAD_KEY_SERVER = "upload_key_server";

    // query key
    public static final String DOWNLOAD_KEY_SERVER = "query_key_server";
    public static final String DOWNLOAD_KEY_LIST = "query_key_id";

    // sign key
    public static final String CERTIFY_KEY_MASTER_KEY_ID = "sign_key_master_key_id";
    public static final String CERTIFY_KEY_PUB_KEY_ID = "sign_key_pub_key_id";
    public static final String CERTIFY_KEY_UIDS = "sign_key_uids";

    // consolidate
    public static final String CONSOLIDATE_RECOVERY = "consolidate_recovery";


    /*
     * possible data keys as result send over messenger
     */

    // encrypt
    public static final String RESULT_BYTES = "encrypted_data";

    // decrypt/verify
    public static final String RESULT_DECRYPTED_BYTES = "decrypted_data";
    public static final String RESULT_DECRYPT_VERIFY_RESULT = "signature";

    // export
    public static final String RESULT_EXPORT = "exported";

    Messenger mMessenger;

    // this attribute can possibly merged with the one above? not sure...
    private AtomicBoolean mActionCanceled = new AtomicBoolean(false);

    public KeychainIntentService() {
        super("KeychainIntentService");
    }

    /**
     * The IntentService calls this method from the default worker thread with the intent that
     * started the service. When this method returns, IntentService stops the service, as
     * appropriate.
     */
    @Override
    protected void onHandleIntent(Intent intent) {

        // We have not been cancelled! (yet)
        mActionCanceled.set(false);

        Bundle extras = intent.getExtras();
        if (extras == null) {
            Log.e(Constants.TAG, "Extras bundle is null!");
            return;
        }

        if (!(extras.containsKey(EXTRA_MESSENGER) || extras.containsKey(EXTRA_DATA) || (intent
                .getAction() == null))) {
            Log.e(Constants.TAG,
                    "Extra bundle must contain a messenger, a data bundle, and an action!");
            return;
        }

        Uri dataUri = intent.getData();

        mMessenger = (Messenger) extras.get(EXTRA_MESSENGER);
        Bundle data = extras.getBundle(EXTRA_DATA);
        if (data == null) {
            Log.e(Constants.TAG, "data extra is null!");
            return;
        }

        OtherHelper.logDebugBundle(data, "EXTRA_DATA");

        String action = intent.getAction();

        // executeServiceMethod action from extra bundle
        if (ACTION_ENCRYPT_SIGN.equals(action)) {
            try {
                /* Input */
                int source = data.get(SOURCE) != null ? data.getInt(SOURCE) : data.getInt(TARGET);
                Bundle resultData = new Bundle();

                long sigMasterKeyId = data.getLong(ENCRYPT_SIGNATURE_MASTER_ID);
                String symmetricPassphrase = data.getString(ENCRYPT_SYMMETRIC_PASSPHRASE);

                boolean useAsciiArmor = data.getBoolean(ENCRYPT_USE_ASCII_ARMOR);
                long encryptionKeyIds[] = data.getLongArray(ENCRYPT_ENCRYPTION_KEYS_IDS);
                int compressionId = data.getInt(ENCRYPT_COMPRESSION_ID);
                int urisCount = data.containsKey(ENCRYPT_INPUT_URIS) ? data.getParcelableArrayList(ENCRYPT_INPUT_URIS).size() : 1;
                for (int i = 0; i < urisCount; i++) {
                    data.putInt(SELECTED_URI, i);
                    InputData inputData = createEncryptInputData(data);
                    OutputStream outStream = createCryptOutputStream(data);
                    String originalFilename = getOriginalFilename(data);

                    /* Operation */
                    PgpSignEncrypt.Builder builder = new PgpSignEncrypt.Builder(
                            new ProviderHelper(this),
                            inputData, outStream
                    );
                    builder.setProgressable(this)
                            .setEnableAsciiArmorOutput(useAsciiArmor)
                            .setVersionHeader(PgpHelper.getVersionForHeader(this))
                            .setCompressionId(compressionId)
                            .setSymmetricEncryptionAlgorithm(
                                    Preferences.getPreferences(this).getDefaultEncryptionAlgorithm())
                            .setEncryptionMasterKeyIds(encryptionKeyIds)
                            .setSymmetricPassphrase(symmetricPassphrase)
                            .setOriginalFilename(originalFilename);

                    try {

                        // Find the appropriate subkey to sign with
                        CachedPublicKeyRing signingRing =
                                new ProviderHelper(this).getCachedPublicKeyRing(sigMasterKeyId);
                        long sigSubKeyId = signingRing.getSignId();

                        // Get its passphrase from cache. It is assumed that this passphrase was
                        // cached prior to the service call.
                        String passphrase = PassphraseCacheService.getCachedPassphrase(this, sigSubKeyId);

                        // Set signature settings
                        builder.setSignatureMasterKeyId(sigMasterKeyId)
                                .setSignatureSubKeyId(sigSubKeyId)
                                .setSignaturePassphrase(passphrase)
                                .setSignatureHashAlgorithm(
                                        Preferences.getPreferences(this).getDefaultHashAlgorithm())
                                .setAdditionalEncryptId(sigMasterKeyId);
                    } catch (PgpGeneralException e) {
                        // encrypt-only
                        // TODO Just silently drop the requested signature? Shouldn't we throw here?
                    }

                    // this assumes that the bytes are cleartext (valid for current implementation!)
                    if (source == IO_BYTES) {
                        builder.setCleartextInput(true);
                    }

                    builder.build().execute();

                    outStream.close();

                    /* Output */

                    finalizeEncryptOutputStream(data, resultData, outStream);

                }

                OtherHelper.logDebugBundle(resultData, "resultData");

                sendMessageToHandler(KeychainIntentServiceHandler.MESSAGE_OKAY, resultData);
            } catch (Exception e) {
                sendErrorToHandler(e);
            }
        } else if (ACTION_DECRYPT_VERIFY.equals(action)) {
            try {
                /* Input */
                String passphrase = data.getString(DECRYPT_PASSPHRASE);

                InputData inputData = createDecryptInputData(data);
                OutputStream outStream = createCryptOutputStream(data);

                /* Operation */

                Bundle resultData = new Bundle();

                // verifyText and decrypt returning additional resultData values for the
                // verification of signatures
                PgpDecryptVerify.Builder builder = new PgpDecryptVerify.Builder(
                        new ProviderHelper(this),
                        new PgpDecryptVerify.PassphraseCache() {
                            @Override
                            public String getCachedPassphrase(long masterKeyId) {
                                try {
                                    return PassphraseCacheService.getCachedPassphrase(
                                            KeychainIntentService.this, masterKeyId);
                                } catch (PassphraseCacheService.KeyNotFoundException e) {
                                    return null;
                                }
                            }
                        },
                        inputData, outStream
                );
                builder.setProgressable(this)
                        .setAllowSymmetricDecryption(true)
                        .setPassphrase(passphrase);

                DecryptVerifyResult decryptVerifyResult = builder.build().execute();

                outStream.close();

                resultData.putParcelable(RESULT_DECRYPT_VERIFY_RESULT, decryptVerifyResult);

                /* Output */

                finalizeDecryptOutputStream(data, resultData, outStream);

                OtherHelper.logDebugBundle(resultData, "resultData");

                sendMessageToHandler(KeychainIntentServiceHandler.MESSAGE_OKAY, resultData);
            } catch (Exception e) {
                sendErrorToHandler(e);
            }
        } else if (ACTION_DECRYPT_METADATA.equals(action)) {
            try {
                /* Input */
                String passphrase = data.getString(DECRYPT_PASSPHRASE);

                InputData inputData = createDecryptInputData(data);

                /* Operation */

                Bundle resultData = new Bundle();

                // verifyText and decrypt returning additional resultData values for the
                // verification of signatures
                PgpDecryptVerify.Builder builder = new PgpDecryptVerify.Builder(
                        new ProviderHelper(this),
                        new PgpDecryptVerify.PassphraseCache() {
                            @Override
                            public String getCachedPassphrase(long masterKeyId) throws PgpDecryptVerify.NoSecretKeyException {
                                try {
                                    return PassphraseCacheService.getCachedPassphrase(
                                            KeychainIntentService.this, masterKeyId);
                                } catch (PassphraseCacheService.KeyNotFoundException e) {
                                    throw new PgpDecryptVerify.NoSecretKeyException();
                                }
                            }
                        },
                        inputData, null
                );
                builder.setProgressable(this)
                        .setAllowSymmetricDecryption(true)
                        .setPassphrase(passphrase)
                        .setDecryptMetadataOnly(true);

                DecryptVerifyResult decryptVerifyResult = builder.build().execute();

                resultData.putParcelable(RESULT_DECRYPT_VERIFY_RESULT, decryptVerifyResult);

                /* Output */
                OtherHelper.logDebugBundle(resultData, "resultData");

                sendMessageToHandler(KeychainIntentServiceHandler.MESSAGE_OKAY, resultData);
            } catch (Exception e) {
                sendErrorToHandler(e);
            }
        } else if (ACTION_EDIT_KEYRING.equals(action)) {
            try {
                /* Input */
                SaveKeyringParcel saveParcel = data.getParcelable(EDIT_KEYRING_PARCEL);
                if (saveParcel == null) {
                    Log.e(Constants.TAG, "bug: missing save_keyring_parcel in data!");
                    return;
                }

                /* Operation */
                PgpKeyOperation keyOperations =
                        new PgpKeyOperation(new ProgressScaler(this, 10, 60, 100), mActionCanceled);
                EditKeyResult modifyResult;

                if (saveParcel.mMasterKeyId != null) {
                    String passphrase = data.getString(EDIT_KEYRING_PASSPHRASE);
                    CanonicalizedSecretKeyRing secRing =
                            new ProviderHelper(this).getCanonicalizedSecretKeyRing(saveParcel.mMasterKeyId);

                    modifyResult = keyOperations.modifySecretKeyRing(secRing, saveParcel, passphrase);
                } else {
                    modifyResult = keyOperations.createSecretKeyRing(saveParcel);
                }

                // If the edit operation didn't succeed, exit here
                if (!modifyResult.success()) {
                    // always return SaveKeyringResult, so create one out of the EditKeyResult
                    SaveKeyringResult saveResult = new SaveKeyringResult(
                            SaveKeyringResult.RESULT_ERROR,
                            modifyResult.getLog(),
                            null);
                    sendMessageToHandler(KeychainIntentServiceHandler.MESSAGE_OKAY, saveResult);
                    return;
                }

                UncachedKeyRing ring = modifyResult.getRing();

                // Check if the action was cancelled
                if (mActionCanceled.get()) {
                    OperationLog log = modifyResult.getLog();
                    // If it wasn't added before, add log entry
                    if (!modifyResult.cancelled()) {
                        log.add(LogLevel.CANCELLED, LogType.MSG_OPERATION_CANCELLED, 0);
                    }
                    // If so, just stop without saving
                    SaveKeyringResult saveResult = new SaveKeyringResult(
                            SaveKeyringResult.RESULT_CANCELLED, log, null);
                    sendMessageToHandler(KeychainIntentServiceHandler.MESSAGE_OKAY, saveResult);
                    return;
                }

                // Save the keyring. The ProviderHelper is initialized with the previous log
                SaveKeyringResult saveResult = new ProviderHelper(this, modifyResult.getLog())
                        .saveSecretKeyRing(ring, new ProgressScaler(this, 60, 95, 100));

                // If the edit operation didn't succeed, exit here
                if (!saveResult.success()) {
                    sendMessageToHandler(KeychainIntentServiceHandler.MESSAGE_OKAY, saveResult);
                    return;
                }

                // cache new passphrase
                if (saveParcel.mNewPassphrase != null) {
                    PassphraseCacheService.addCachedPassphrase(this, ring.getMasterKeyId(),
                            saveParcel.mNewPassphrase, ring.getPublicKey().getPrimaryUserIdWithFallback());
                }

                setProgress(R.string.progress_done, 100, 100);

                // make sure new data is synced into contacts
                ContactSyncAdapterService.requestSync();

                /* Output */
                sendMessageToHandler(KeychainIntentServiceHandler.MESSAGE_OKAY, saveResult);
            } catch (Exception e) {
                sendErrorToHandler(e);
            }

        } else if (ACTION_DELETE_FILE_SECURELY.equals(action)) {
            try {
                /* Input */
                String deleteFile = data.getString(DELETE_FILE);

                /* Operation */
                try {
                    PgpHelper.deleteFileSecurely(this, this, new File(deleteFile));
                } catch (FileNotFoundException e) {
                    throw new PgpGeneralException(
                            getString(R.string.error_file_not_found, deleteFile));
                } catch (IOException e) {
                    throw new PgpGeneralException(getString(R.string.error_file_delete_failed,
                            deleteFile));
                }

                /* Output */
                sendMessageToHandler(KeychainIntentServiceHandler.MESSAGE_OKAY);
            } catch (Exception e) {
                sendErrorToHandler(e);
            }
        } else if (ACTION_IMPORT_KEYRING.equals(action)) {
            try {

                List<ParcelableKeyRing> entries;
                if (data.containsKey(IMPORT_KEY_LIST)) {
                    // get entries from intent
                    entries = data.getParcelableArrayList(IMPORT_KEY_LIST);
                } else {
                    // get entries from cached file
                    ParcelableFileCache<ParcelableKeyRing> cache =
                            new ParcelableFileCache<ParcelableKeyRing>(this, "key_import.pcl");
                    entries = cache.readCacheIntoList();
                }

                ProviderHelper providerHelper = new ProviderHelper(this);
                PgpImportExport pgpImportExport = new PgpImportExport(
                        this, providerHelper, this, mActionCanceled);
                ImportKeyResult result = pgpImportExport.importKeyRings(entries);

                // we do this even on failure or cancellation!
                if (result.mSecret > 0) {
                    // cannot cancel from here on out!
                    sendMessageToHandler(KeychainIntentServiceHandler.MESSAGE_PREVENT_CANCEL);
                    providerHelper.consolidateDatabaseStep1(this);
                }

                // make sure new data is synced into contacts
                ContactSyncAdapterService.requestSync();

                sendMessageToHandler(KeychainIntentServiceHandler.MESSAGE_OKAY, result);
            } catch (Exception e) {
                sendErrorToHandler(e);
            }
        } else if (ACTION_EXPORT_KEYRING.equals(action)) {
            try {

                boolean exportSecret = data.getBoolean(EXPORT_SECRET, false);
                long[] masterKeyIds = data.getLongArray(EXPORT_KEY_RING_MASTER_KEY_ID);
                String outputFile = data.getString(EXPORT_FILENAME);
                Uri outputUri = data.getParcelable(EXPORT_URI);

                // If not exporting all keys get the masterKeyIds of the keys to export from the intent
                boolean exportAll = data.getBoolean(EXPORT_ALL);

                if (outputFile != null) {
                    // check if storage is ready
                    if (!FileHelper.isStorageMounted(outputFile)) {
                        throw new PgpGeneralException(getString(R.string.error_external_storage_not_ready));
                    }
                }

                ArrayList<Long> publicMasterKeyIds = new ArrayList<Long>();
                ArrayList<Long> secretMasterKeyIds = new ArrayList<Long>();

                String selection = null;
                if (!exportAll) {
                    selection = KeychainDatabase.Tables.KEYS + "." + KeyRings.MASTER_KEY_ID + " IN( ";
                    for (long l : masterKeyIds) {
                        selection += Long.toString(l) + ",";
                    }
                    selection = selection.substring(0, selection.length() - 1) + " )";
                }

                Cursor cursor = getContentResolver().query(KeyRings.buildUnifiedKeyRingsUri(),
                        new String[]{KeyRings.MASTER_KEY_ID, KeyRings.HAS_ANY_SECRET},
                        selection, null, null);
                try {
                    if (cursor != null && cursor.moveToFirst()) do {
                        // export public either way
                        publicMasterKeyIds.add(cursor.getLong(0));
                        // add secret if available (and requested)
                        if (exportSecret && cursor.getInt(1) != 0)
                            secretMasterKeyIds.add(cursor.getLong(0));
                    } while (cursor.moveToNext());
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }

                OutputStream outStream;
                if (outputFile != null) {
                    outStream = new FileOutputStream(outputFile);
                } else {
                    outStream = getContentResolver().openOutputStream(outputUri);
                }

                PgpImportExport pgpImportExport = new PgpImportExport(this, new ProviderHelper(this), this);
                Bundle resultData = pgpImportExport
                        .exportKeyRings(publicMasterKeyIds, secretMasterKeyIds, outStream);

                if (mActionCanceled.get() && outputFile != null) {
                    new File(outputFile).delete();
                }

                sendMessageToHandler(KeychainIntentServiceHandler.MESSAGE_OKAY, resultData);
            } catch (Exception e) {
                sendErrorToHandler(e);
            }
        } else if (ACTION_UPLOAD_KEYRING.equals(action)) {
            try {

                /* Input */
                String keyServer = data.getString(UPLOAD_KEY_SERVER);
                // and dataUri!

                /* Operation */
                HkpKeyserver server = new HkpKeyserver(keyServer);

                ProviderHelper providerHelper = new ProviderHelper(this);
                CanonicalizedPublicKeyRing keyring = providerHelper.getCanonicalizedPublicKeyRing(dataUri);
                PgpImportExport pgpImportExport = new PgpImportExport(this, new ProviderHelper(this), this);

                try {
                    pgpImportExport.uploadKeyRingToServer(server, keyring);
                } catch (Keyserver.AddKeyException e) {
                    throw new PgpGeneralException("Unable to export key to selected server");
                }

                sendMessageToHandler(KeychainIntentServiceHandler.MESSAGE_OKAY);
            } catch (Exception e) {
                sendErrorToHandler(e);
            }
        } else if (ACTION_DOWNLOAD_AND_IMPORT_KEYS.equals(action) || ACTION_IMPORT_KEYBASE_KEYS.equals(action)) {
            ArrayList<ImportKeysListEntry> entries = data.getParcelableArrayList(DOWNLOAD_KEY_LIST);

                // this downloads the keys and places them into the ImportKeysListEntry entries
                String keyServer = data.getString(DOWNLOAD_KEY_SERVER);

                ArrayList<ParcelableKeyRing> keyRings = new ArrayList<ParcelableKeyRing>(entries.size());
                for (ImportKeysListEntry entry : entries) {
                    try {
                        Keyserver server;
                        if (entry.getOrigin() == null) {
                            server = new HkpKeyserver(keyServer);
                        } else if (KeybaseKeyserver.ORIGIN.equals(entry.getOrigin())) {
                            server = new KeybaseKeyserver();
                        } else {
                            server = new HkpKeyserver(entry.getOrigin());
                        }

                        // if available use complete fingerprint for get request
                        byte[] downloadedKeyBytes;
                        if (KeybaseKeyserver.ORIGIN.equals(entry.getOrigin())) {
                            downloadedKeyBytes = server.get(entry.getExtraData()).getBytes();
                        } else if (entry.getFingerprintHex() != null) {
                            downloadedKeyBytes = server.get("0x" + entry.getFingerprintHex()).getBytes();
                        } else {
                            downloadedKeyBytes = server.get(entry.getKeyIdHex()).getBytes();
                        }

                        // save key bytes in entry object for doing the
                        // actual import afterwards
                        keyRings.add(new ParcelableKeyRing(downloadedKeyBytes, entry.getFingerprintHex()));
                    } catch (Exception e) {
                        sendErrorToHandler(e);
                    }
                }

                Intent importIntent = new Intent(this, KeychainIntentService.class);
                importIntent.setAction(ACTION_IMPORT_KEYRING);

                Bundle importData = new Bundle();
                // This is not going through binder, nothing to fear of
                importData.putParcelableArrayList(IMPORT_KEY_LIST, keyRings);
                importIntent.putExtra(EXTRA_DATA, importData);
                importIntent.putExtra(EXTRA_MESSENGER, mMessenger);

                // now import it with this service
                onHandleIntent(importIntent);

                // result is handled in ACTION_IMPORT_KEYRING
        } else if (ACTION_CERTIFY_KEYRING.equals(action)) {
            try {

                /* Input */
                long masterKeyId = data.getLong(CERTIFY_KEY_MASTER_KEY_ID);
                long pubKeyId = data.getLong(CERTIFY_KEY_PUB_KEY_ID);
                ArrayList<String> userIds = data.getStringArrayList(CERTIFY_KEY_UIDS);

                /* Operation */
                String signaturePassphrase = PassphraseCacheService.getCachedPassphrase(this,
                        masterKeyId);
                if (signaturePassphrase == null) {
                    throw new PgpGeneralException("Unable to obtain passphrase");
                }

                ProviderHelper providerHelper = new ProviderHelper(this);
                CanonicalizedPublicKeyRing publicRing = providerHelper.getCanonicalizedPublicKeyRing(pubKeyId);
                CanonicalizedSecretKeyRing secretKeyRing = providerHelper.getCanonicalizedSecretKeyRing(masterKeyId);
                CanonicalizedSecretKey certificationKey = secretKeyRing.getSecretKey();
                if (!certificationKey.unlock(signaturePassphrase)) {
                    throw new PgpGeneralException("Error extracting key (bad passphrase?)");
                }
                // TODO: supply nfc stuff
                UncachedKeyRing newRing = certificationKey.certifyUserIds(publicRing, userIds, null, null);

                // store the signed key in our local cache
                providerHelper.savePublicKeyRing(newRing);
                sendMessageToHandler(KeychainIntentServiceHandler.MESSAGE_OKAY);

            } catch (Exception e) {
                sendErrorToHandler(e);
            }

        } else if (ACTION_DELETE.equals(action)) {

            try {

                long[] masterKeyIds = data.getLongArray(DELETE_KEY_LIST);
                boolean isSecret = data.getBoolean(DELETE_IS_SECRET);

                if (masterKeyIds.length == 0) {
                    throw new PgpGeneralException("List of keys to delete is empty");
                }

                if (isSecret && masterKeyIds.length > 1) {
                    throw new PgpGeneralException("Secret keys can only be deleted individually!");
                }

                boolean success = false;
                for (long masterKeyId : masterKeyIds) {
                    int count = getContentResolver().delete(
                            KeyRingData.buildPublicKeyRingUri(masterKeyId), null, null
                    );
                    success |= count > 0;
                }

                if (isSecret && success) {
                    new ProviderHelper(this).consolidateDatabaseStep1(this);
                }

                if (success) {
                    // make sure new data is synced into contacts
                    ContactSyncAdapterService.requestSync();

                    sendMessageToHandler(KeychainIntentServiceHandler.MESSAGE_OKAY);
                }

            } catch (Exception e) {
                sendErrorToHandler(e);
            }

        } else if (ACTION_CONSOLIDATE.equals(action)) {
            ConsolidateResult result;
            if (data.containsKey(CONSOLIDATE_RECOVERY) && data.getBoolean(CONSOLIDATE_RECOVERY)) {
                result = new ProviderHelper(this).consolidateDatabaseStep2(this);
            } else {
                result = new ProviderHelper(this).consolidateDatabaseStep1(this);
            }
            sendMessageToHandler(KeychainIntentServiceHandler.MESSAGE_OKAY, result);
        }

    }

    private void sendErrorToHandler(Exception e) {
        // TODO: Implement a better exception handling here
        // contextualize the exception, if necessary
        String message;
        if (e instanceof PgpGeneralMsgIdException) {
            e = ((PgpGeneralMsgIdException) e).getContextualized(this);
            message = e.getMessage();
        } else if (e instanceof PgpSignEncrypt.KeyExtractionException) {
            message = getString(R.string.error_could_not_extract_private_key);
        } else if (e instanceof PgpSignEncrypt.NoPassphraseException) {
            message = getString(R.string.error_no_signature_passphrase);
        } else if (e instanceof PgpSignEncrypt.NoSigningKeyException) {
            message = getString(R.string.error_no_signature_key);
        } else {
            message = e.getMessage();
        }

        Log.e(Constants.TAG, "KeychainIntentService Exception: ", e);

        Bundle data = new Bundle();
        data.putString(KeychainIntentServiceHandler.DATA_ERROR, message);
        sendMessageToHandler(KeychainIntentServiceHandler.MESSAGE_EXCEPTION, null, data);
    }

    private void sendMessageToHandler(Integer arg1, Integer arg2, Bundle data) {

        Message msg = Message.obtain();
        assert msg != null;
        msg.arg1 = arg1;
        if (arg2 != null) {
            msg.arg2 = arg2;
        }
        if (data != null) {
            msg.setData(data);
        }

        try {
            mMessenger.send(msg);
        } catch (RemoteException e) {
            Log.w(Constants.TAG, "Exception sending message, Is handler present?", e);
        } catch (NullPointerException e) {
            Log.w(Constants.TAG, "Messenger is null!", e);
        }
    }

    private void sendMessageToHandler(Integer arg1, OperationResultParcel data) {
        Bundle bundle = new Bundle();
        bundle.putParcelable(OperationResultParcel.EXTRA_RESULT, data);
        sendMessageToHandler(arg1, null, bundle);
    }

    private void sendMessageToHandler(Integer arg1, Bundle data) {
        sendMessageToHandler(arg1, null, data);
    }

    private void sendMessageToHandler(Integer arg1) {
        sendMessageToHandler(arg1, null, null);
    }

    /**
     * Set progress of ProgressDialog by sending message to handler on UI thread
     */
    public void setProgress(String message, int progress, int max) {
        Log.d(Constants.TAG, "Send message by setProgress with progress=" + progress + ", max="
                + max);

        Bundle data = new Bundle();
        if (message != null) {
            data.putString(KeychainIntentServiceHandler.DATA_MESSAGE, message);
        }
        data.putInt(KeychainIntentServiceHandler.DATA_PROGRESS, progress);
        data.putInt(KeychainIntentServiceHandler.DATA_PROGRESS_MAX, max);

        sendMessageToHandler(KeychainIntentServiceHandler.MESSAGE_UPDATE_PROGRESS, null, data);
    }

    public void setProgress(int resourceId, int progress, int max) {
        setProgress(getString(resourceId), progress, max);
    }

    public void setProgress(int progress, int max) {
        setProgress(null, progress, max);
    }

    private InputData createDecryptInputData(Bundle data) throws IOException, PgpGeneralException {
        return createCryptInputData(data, DECRYPT_CIPHERTEXT_BYTES);
    }

    private InputData createEncryptInputData(Bundle data) throws IOException, PgpGeneralException {
        return createCryptInputData(data, ENCRYPT_MESSAGE_BYTES);
    }

    private InputData createCryptInputData(Bundle data, String bytesName) throws PgpGeneralException, IOException {
        int source = data.get(SOURCE) != null ? data.getInt(SOURCE) : data.getInt(TARGET);
        switch (source) {
            case IO_BYTES: /* encrypting bytes directly */
                byte[] bytes = data.getByteArray(bytesName);
                return new InputData(new ByteArrayInputStream(bytes), bytes.length);

            case IO_URI: /* encrypting content uri */
                Uri providerUri = data.getParcelable(ENCRYPT_INPUT_URI);

                // InputStream
                return new InputData(getContentResolver().openInputStream(providerUri), FileHelper.getFileSize(this, providerUri, 0));

            case IO_URIS:
                providerUri = data.<Uri>getParcelableArrayList(ENCRYPT_INPUT_URIS).get(data.getInt(SELECTED_URI));

                // InputStream
                return new InputData(getContentResolver().openInputStream(providerUri), FileHelper.getFileSize(this, providerUri, 0));

            default:
                throw new PgpGeneralException("No target choosen!");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (ACTION_CANCEL.equals(intent.getAction())) {
            mActionCanceled.set(true);
            return START_NOT_STICKY;
        }
        return super.onStartCommand(intent, flags, startId);
    }

    private String getOriginalFilename(Bundle data) throws PgpGeneralException, FileNotFoundException {
        int target = data.getInt(TARGET);
        switch (target) {
            case IO_BYTES:
                return "";

            case IO_URI:
                Uri providerUri = data.getParcelable(ENCRYPT_INPUT_URI);

                return FileHelper.getFilename(this, providerUri);

            case IO_URIS:
                providerUri = data.<Uri>getParcelableArrayList(ENCRYPT_INPUT_URIS).get(data.getInt(SELECTED_URI));

                return FileHelper.getFilename(this, providerUri);

            default:
                throw new PgpGeneralException("No target choosen!");
        }
    }

    private OutputStream createCryptOutputStream(Bundle data) throws PgpGeneralException, FileNotFoundException {
        int target = data.getInt(TARGET);
        switch (target) {
            case IO_BYTES:
                return new ByteArrayOutputStream();

            case IO_URI:
                Uri providerUri = data.getParcelable(ENCRYPT_OUTPUT_URI);

                return getContentResolver().openOutputStream(providerUri);

            case IO_URIS:
                providerUri = data.<Uri>getParcelableArrayList(ENCRYPT_OUTPUT_URIS).get(data.getInt(SELECTED_URI));

                return getContentResolver().openOutputStream(providerUri);

            default:
                throw new PgpGeneralException("No target choosen!");
        }
    }

    private void finalizeEncryptOutputStream(Bundle data, Bundle resultData, OutputStream outStream) {
        finalizeCryptOutputStream(data, resultData, outStream, RESULT_BYTES);
    }

    private void finalizeDecryptOutputStream(Bundle data, Bundle resultData, OutputStream outStream) {
        finalizeCryptOutputStream(data, resultData, outStream, RESULT_DECRYPTED_BYTES);
    }

    private void finalizeCryptOutputStream(Bundle data, Bundle resultData, OutputStream outStream, String bytesName) {
        int target = data.getInt(TARGET);
        switch (target) {
            case IO_BYTES:
                byte output[] = ((ByteArrayOutputStream) outStream).toByteArray();
                resultData.putByteArray(bytesName, output);
                break;
            case IO_URI:
            case IO_URIS:
                // nothing, output was written, just send okay and verification bundle

                break;
        }
    }
}
