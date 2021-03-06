/*
 * Copyright (C) 2014 Dominik Schürmann <dominik@dominikschuermann.de>
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

package org.sufficientlysecure.keychain.ui;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.R;
import org.sufficientlysecure.keychain.helper.FileHelper;
import org.sufficientlysecure.keychain.service.results.DecryptVerifyResult;
import org.sufficientlysecure.keychain.service.KeychainIntentService;
import org.sufficientlysecure.keychain.service.KeychainIntentServiceHandler;
import org.sufficientlysecure.keychain.ui.dialog.DeleteFileDialogFragment;
import org.sufficientlysecure.keychain.ui.dialog.PassphraseDialogFragment;
import org.sufficientlysecure.keychain.util.Log;
import org.sufficientlysecure.keychain.util.Notify;

import java.io.File;

public class DecryptFileFragment extends DecryptFragment {
    public static final String ARG_URI = "uri";
    public static final String ARG_FROM_VIEW_INTENT = "view_intent";

    private static final int REQUEST_CODE_INPUT = 0x00007003;
    private static final int REQUEST_CODE_OUTPUT = 0x00007007;

    // view
    private TextView mFilename;
    private CheckBox mDeleteAfter;
    private View mDecryptButton;

    // model
    private Uri mInputUri = null;
    private Uri mOutputUri = null;

    /**
     * Inflate the layout for this fragment
     */
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.decrypt_file_fragment, container, false);

        mFilename = (TextView) view.findViewById(R.id.decrypt_file_filename);
        mDeleteAfter = (CheckBox) view.findViewById(R.id.decrypt_file_delete_after_decryption);
        mDecryptButton = view.findViewById(R.id.decrypt_file_action_decrypt);
        view.findViewById(R.id.decrypt_file_browse).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    FileHelper.openDocument(DecryptFileFragment.this, "*/*", REQUEST_CODE_INPUT);
                } else {
                    FileHelper.openFile(DecryptFileFragment.this, mInputUri, "*/*",
                            REQUEST_CODE_INPUT);
                }
            }
        });
        mDecryptButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                decryptAction();
            }
        });

        return view;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        setInputUri(getArguments().<Uri>getParcelable(ARG_URI));
    }

    private void setInputUri(Uri inputUri) {
        if (inputUri == null) {
            mInputUri = null;
            mFilename.setText("");
            return;
        }

        mInputUri = inputUri;
        mFilename.setText(FileHelper.getFilename(getActivity(), mInputUri));
    }

    private void decryptAction() {
        if (mInputUri == null) {
            Notify.showNotify(getActivity(), R.string.no_file_selected, Notify.Style.ERROR);
            return;
        }

//        askForOutputFilename();
        decryptOriginalFilename(null);
    }

    private String removeEncryptedAppend(String name) {
        if (name.endsWith(".asc") || name.endsWith(".gpg") || name.endsWith(".pgp")) {
            return name.substring(0, name.length() - 4);
        }
        return name;
    }

    private void askForOutputFilename(String originalFilename) {
        String targetName;
        if (!TextUtils.isEmpty(originalFilename)) {
            targetName = originalFilename;
        } else {
            targetName = removeEncryptedAppend(FileHelper.getFilename(getActivity(), mInputUri));
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            File file = new File(mInputUri.getPath());
            File parentDir = file.exists() ? file.getParentFile() : Constants.Path.APP_DIR;
            File targetFile = new File(parentDir, targetName);
            FileHelper.saveFile(this, getString(R.string.title_decrypt_to_file),
                    getString(R.string.specify_file_to_decrypt_to), targetFile, REQUEST_CODE_OUTPUT);
        } else {
            FileHelper.saveDocument(this, "*/*", targetName, REQUEST_CODE_OUTPUT);
        }
    }

    private void decryptOriginalFilename(String passphrase) {
        Log.d(Constants.TAG, "decryptOriginalFilename");

        Intent intent = new Intent(getActivity(), KeychainIntentService.class);

        // fill values for this action
        Bundle data = new Bundle();
        intent.setAction(KeychainIntentService.ACTION_DECRYPT_METADATA);

        // data
        Log.d(Constants.TAG, "mInputUri=" + mInputUri + ", mOutputUri=" + mOutputUri);

        data.putInt(KeychainIntentService.SOURCE, KeychainIntentService.IO_URI);
        data.putParcelable(KeychainIntentService.ENCRYPT_INPUT_URI, mInputUri);

        data.putInt(KeychainIntentService.TARGET, KeychainIntentService.IO_URI);
        data.putParcelable(KeychainIntentService.ENCRYPT_OUTPUT_URI, mOutputUri);

        data.putString(KeychainIntentService.DECRYPT_PASSPHRASE, passphrase);

        intent.putExtra(KeychainIntentService.EXTRA_DATA, data);

        // Message is received after decrypting is done in KeychainIntentService
        KeychainIntentServiceHandler saveHandler = new KeychainIntentServiceHandler(getActivity(),
                getString(R.string.progress_decrypting), ProgressDialog.STYLE_HORIZONTAL) {
            public void handleMessage(Message message) {
                // handle messages by standard KeychainIntentServiceHandler first
                super.handleMessage(message);

                if (message.arg1 == KeychainIntentServiceHandler.MESSAGE_OKAY) {
                    // get returned data bundle
                    Bundle returnData = message.getData();

                    DecryptVerifyResult result =
                            returnData.getParcelable(KeychainIntentService.RESULT_DECRYPT_VERIFY_RESULT);

                    switch (result.getResult()) {
                        case DecryptVerifyResult.RESULT_PENDING_ASYM_PASSPHRASE:
                            showPassphraseDialog(result.getKeyIdPassphraseNeeded());
                            return;
                        case DecryptVerifyResult.RESULT_PENDING_SYM_PASSPHRASE:
                            showPassphraseDialog(Constants.key.symmetric);
                            return;
                    }

                    // go on...
                    askForOutputFilename(result.getDecryptMetadata().getFilename());
                }
            }
        };

        // Create a new Messenger for the communication back
        Messenger messenger = new Messenger(saveHandler);
        intent.putExtra(KeychainIntentService.EXTRA_MESSENGER, messenger);

        // show progress dialog
        saveHandler.showProgressDialog(getActivity());

        // start service with intent
        getActivity().startService(intent);
    }

    protected void showPassphraseDialogForFilename(long keyId) {
        PassphraseDialogFragment.show(getActivity(), keyId,
                new Handler() {
                    @Override
                    public void handleMessage(Message message) {
                        if (message.what == PassphraseDialogFragment.MESSAGE_OKAY) {
                            String passphrase =
                                    message.getData().getString(PassphraseDialogFragment.MESSAGE_DATA_PASSPHRASE);
                            decryptOriginalFilename(passphrase);
                        }
                    }
                }
        );
    }

    @Override
    protected void decryptStart(String passphrase) {
        Log.d(Constants.TAG, "decryptStart");

        // Send all information needed to service to decrypt in other thread
        Intent intent = new Intent(getActivity(), KeychainIntentService.class);

        // fill values for this action
        Bundle data = new Bundle();

        intent.setAction(KeychainIntentService.ACTION_DECRYPT_VERIFY);

        // data
        Log.d(Constants.TAG, "mInputUri=" + mInputUri + ", mOutputUri=" + mOutputUri);

        data.putInt(KeychainIntentService.SOURCE, KeychainIntentService.IO_URI);
        data.putParcelable(KeychainIntentService.ENCRYPT_INPUT_URI, mInputUri);

        data.putInt(KeychainIntentService.TARGET, KeychainIntentService.IO_URI);
        data.putParcelable(KeychainIntentService.ENCRYPT_OUTPUT_URI, mOutputUri);

        data.putString(KeychainIntentService.DECRYPT_PASSPHRASE, passphrase);

        intent.putExtra(KeychainIntentService.EXTRA_DATA, data);

        // Message is received after decrypting is done in KeychainIntentService
        KeychainIntentServiceHandler saveHandler = new KeychainIntentServiceHandler(getActivity(),
                getString(R.string.progress_decrypting), ProgressDialog.STYLE_HORIZONTAL) {
            public void handleMessage(Message message) {
                // handle messages by standard KeychainIntentServiceHandler first
                super.handleMessage(message);

                if (message.arg1 == KeychainIntentServiceHandler.MESSAGE_OKAY) {
                    // get returned data bundle
                    Bundle returnData = message.getData();

                    DecryptVerifyResult result =
                            returnData.getParcelable(KeychainIntentService.RESULT_DECRYPT_VERIFY_RESULT);

                    if (result.isPending()) {
                        switch (result.getResult()) {
                            case DecryptVerifyResult.RESULT_PENDING_ASYM_PASSPHRASE:
                                showPassphraseDialog(result.getKeyIdPassphraseNeeded());
                                return;
                            case DecryptVerifyResult.RESULT_PENDING_SYM_PASSPHRASE:
                                showPassphraseDialog(Constants.key.symmetric);
                                return;
                        }
                        // error, we can't work with this!
                        result.createNotify(getActivity());
                        return;
                    }

                    // display signature result in activity
                    onResult(result);

                    if (mDeleteAfter.isChecked()) {
                        // Create and show dialog to delete original file
                        DeleteFileDialogFragment deleteFileDialog = DeleteFileDialogFragment.newInstance(mInputUri);
                        deleteFileDialog.show(getActivity().getSupportFragmentManager(), "deleteDialog");
                        setInputUri(null);
                    }

                    /*
                    // A future open after decryption feature
                    if () {
                        Intent viewFile = new Intent(Intent.ACTION_VIEW);
                        viewFile.setData(mOutputUri);
                        startActivity(viewFile);
                    }
                    */
                }

            }
        };

        // Create a new Messenger for the communication back
        Messenger messenger = new Messenger(saveHandler);
        intent.putExtra(KeychainIntentService.EXTRA_MESSENGER, messenger);

        // show progress dialog
        saveHandler.showProgressDialog(getActivity());

        // start service with intent
        getActivity().startService(intent);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_CODE_INPUT: {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    setInputUri(data.getData());
                }
                return;
            }
            case REQUEST_CODE_OUTPUT: {
                // This happens after output file was selected, so start our operation
                if (resultCode == Activity.RESULT_OK && data != null) {
                    mOutputUri = data.getData();
                    decryptStart(null);
                }
                return;
            }

            default: {
                super.onActivityResult(requestCode, resultCode, data);

                break;
            }
        }
    }
}
