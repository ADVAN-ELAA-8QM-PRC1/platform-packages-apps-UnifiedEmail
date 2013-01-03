/**
 * Copyright (c) 2011, Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.mail.compose;

import android.app.ActionBar;
import android.app.ActionBar.OnNavigationListener;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.app.LoaderManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.CursorLoader;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.Loader;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Parcelable;
import android.os.ParcelFileDescriptor;
import android.provider.BaseColumns;
import android.text.Editable;
import android.text.Html;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.util.Rfc822Token;
import android.text.util.Rfc822Tokenizer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.android.common.Rfc822Validator;
import com.android.ex.chips.RecipientEditTextView;
import com.android.mail.R;
import com.android.mail.compose.AttachmentsView.AttachmentAddedOrDeletedListener;
import com.android.mail.compose.AttachmentsView.AttachmentFailureException;
import com.android.mail.compose.FromAddressSpinner.OnAccountChangedListener;
import com.android.mail.compose.QuotedTextView.RespondInlineListener;
import com.android.mail.providers.Account;
import com.android.mail.providers.Address;
import com.android.mail.providers.Attachment;
import com.android.mail.providers.MailAppProvider;
import com.android.mail.providers.Message;
import com.android.mail.providers.MessageModification;
import com.android.mail.providers.ReplyFromAccount;
import com.android.mail.providers.Settings;
import com.android.mail.providers.UIProvider;
import com.android.mail.providers.UIProvider.AccountCapabilities;
import com.android.mail.providers.UIProvider.DraftType;
import com.android.mail.ui.MailActivity;
import com.android.mail.ui.WaitFragment;
import com.android.mail.ui.AttachmentTile.AttachmentPreview;
import com.android.mail.utils.AccountUtils;
import com.android.mail.utils.AttachmentUtils;
import com.android.mail.utils.ContentProviderTask;
import com.android.mail.utils.LogTag;
import com.android.mail.utils.LogUtils;
import com.android.mail.utils.Utils;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ComposeActivity extends Activity implements OnClickListener, OnNavigationListener,
        RespondInlineListener, DialogInterface.OnClickListener, TextWatcher,
        AttachmentAddedOrDeletedListener, OnAccountChangedListener, LoaderManager.LoaderCallbacks<Cursor>,
        TextView.OnEditorActionListener {
    // Identifiers for which type of composition this is
    static final int COMPOSE = -1;
    static final int REPLY = 0;
    static final int REPLY_ALL = 1;
    static final int FORWARD = 2;
    static final int EDIT_DRAFT = 3;

    // Integer extra holding one of the above compose action
    protected static final String EXTRA_ACTION = "action";

    private static final String EXTRA_SHOW_CC = "showCc";
    private static final String EXTRA_SHOW_BCC = "showBcc";
    private static final String EXTRA_RESPONDED_INLINE = "respondedInline";
    private static final String EXTRA_SAVE_ENABLED = "saveEnabled";

    private static final String UTF8_ENCODING_NAME = "UTF-8";

    private static final String MAIL_TO = "mailto";

    private static final String EXTRA_SUBJECT = "subject";

    private static final String EXTRA_BODY = "body";

    protected static final String EXTRA_FROM_ACCOUNT_STRING = "fromAccountString";

    private static final String EXTRA_ATTACHMENT_PREVIEWS = "attachmentPreviews";

    // Extra that we can get passed from other activities
    private static final String EXTRA_TO = "to";
    private static final String EXTRA_CC = "cc";
    private static final String EXTRA_BCC = "bcc";

    // List of all the fields
    static final String[] ALL_EXTRAS = { EXTRA_SUBJECT, EXTRA_BODY, EXTRA_TO, EXTRA_CC, EXTRA_BCC };

    private static SendOrSaveCallback sTestSendOrSaveCallback = null;
    // Map containing information about requests to create new messages, and the id of the
    // messages that were the result of those requests.
    //
    // This map is used when the activity that initiated the save a of a new message, is killed
    // before the save has completed (and when we know the id of the newly created message).  When
    // a save is completed, the service that is running in the background, will update the map
    //
    // When a new ComposeActivity instance is created, it will attempt to use the information in
    // the previously instantiated map.  If ComposeActivity.onCreate() is called, with a bundle
    // (restoring data from a previous instance), and the map hasn't been created, we will attempt
    // to populate the map with data stored in shared preferences.
    // FIXME: values in this map are never read.
    private static ConcurrentHashMap<Integer, Long> sRequestMessageIdMap = null;
    // Key used to store the above map
    private static final String CACHED_MESSAGE_REQUEST_IDS_KEY = "cache-message-request-ids";
    /**
     * Notifies the {@code Activity} that the caller is an Email
     * {@code Activity}, so that the back behavior may be modified accordingly.
     *
     * @see #onAppUpPressed
     */
    public static final String EXTRA_FROM_EMAIL_TASK = "fromemail";

    public static final String EXTRA_ATTACHMENTS = "attachments";

    //  If this is a reply/forward then this extra will hold the original message
    private static final String EXTRA_IN_REFERENCE_TO_MESSAGE = "in-reference-to-message";
    // If this is a reply/forward then this extra will hold a uri we must query
    // to get the original message.
    protected static final String EXTRA_IN_REFERENCE_TO_MESSAGE_URI = "in-reference-to-message-uri";
    // If this is an action to edit an existing draft message, this extra will hold the
    // draft message
    private static final String ORIGINAL_DRAFT_MESSAGE = "original-draft-message";
    private static final String END_TOKEN = ", ";
    private static final String LOG_TAG = LogTag.getLogTag();
    // Request numbers for activities we start
    private static final int RESULT_PICK_ATTACHMENT = 1;
    private static final int RESULT_CREATE_ACCOUNT = 2;
    // TODO(mindyp) set mime-type for auto send?
    public static final String AUTO_SEND_ACTION = "com.android.mail.action.AUTO_SEND";

    private static final String EXTRA_SELECTED_REPLY_FROM_ACCOUNT = "replyFromAccount";
    private static final String EXTRA_REQUEST_ID = "requestId";
    private static final String EXTRA_FOCUS_SELECTION_START = "focusSelectionStart";
    private static final String EXTRA_FOCUS_SELECTION_END = null;
    private static final String EXTRA_MESSAGE = "extraMessage";
    private static final int REFERENCE_MESSAGE_LOADER = 0;
    private static final int LOADER_ACCOUNT_CURSOR = 1;
    private static final String EXTRA_SELECTED_ACCOUNT = "selectedAccount";
    private static final String TAG_WAIT = "wait-fragment";
    private static final String MIME_TYPE_PHOTO = "image/*";
    private static final String MIME_TYPE_VIDEO = "video/*";

    /**
     * A single thread for running tasks in the background.
     */
    private Handler mSendSaveTaskHandler = null;
    private RecipientEditTextView mTo;
    private RecipientEditTextView mCc;
    private RecipientEditTextView mBcc;
    private Button mCcBccButton;
    private CcBccView mCcBccView;
    private AttachmentsView mAttachmentsView;
    private Account mAccount;
    private ReplyFromAccount mReplyFromAccount;
    private Settings mCachedSettings;
    private Rfc822Validator mValidator;
    private TextView mSubject;

    private ComposeModeAdapter mComposeModeAdapter;
    private int mComposeMode = -1;
    private boolean mForward;
    private String mRecipient;
    private QuotedTextView mQuotedTextView;
    private EditText mBodyView;
    private View mFromStatic;
    private TextView mFromStaticText;
    private View mFromSpinnerWrapper;
    @VisibleForTesting
    protected FromAddressSpinner mFromSpinner;
    private boolean mAddingAttachment;
    private boolean mAttachmentsChanged;
    private boolean mTextChanged;
    private boolean mReplyFromChanged;
    private MenuItem mSave;
    private MenuItem mSend;
    private AlertDialog mRecipientErrorDialog;
    private AlertDialog mSendConfirmDialog;
    @VisibleForTesting
    protected Message mRefMessage;
    private long mDraftId = UIProvider.INVALID_MESSAGE_ID;
    private Message mDraft;
    private ReplyFromAccount mDraftAccount;
    private Object mDraftLock = new Object();
    private View mPhotoAttachmentsButton;
    private View mVideoAttachmentsButton;

    /**
     * Boolean indicating whether ComposeActivity was launched from a Gmail controlled view.
     */
    private boolean mLaunchedFromEmail = false;
    private RecipientTextWatcher mToListener;
    private RecipientTextWatcher mCcListener;
    private RecipientTextWatcher mBccListener;
    private Uri mRefMessageUri;
    private Bundle mSavedInstanceState;


    // Array of the outstanding send or save tasks.  Access is synchronized
    // with the object itself
    /* package for testing */
    @VisibleForTesting
    public ArrayList<SendOrSaveTask> mActiveTasks = Lists.newArrayList();
    // FIXME: this variable is never read. related to sRequestMessageIdMap.
    private int mRequestId;
    private String mSignature;
    private Account[] mAccounts;
    private boolean mRespondedInline;

    /**
     * Can be called from a non-UI thread.
     */
    public static void editDraft(Context launcher, Account account, Message message) {
        launch(launcher, account, message, EDIT_DRAFT);
    }

    /**
     * Can be called from a non-UI thread.
     */
    public static void compose(Context launcher, Account account) {
        launch(launcher, account, null, COMPOSE);
    }

    /**
     * Can be called from a non-UI thread.
     */
    public static Intent createReplyIntent(final Context launcher, final Account account,
            final Uri messageUri, final boolean isReplyAll) {
        return createActionIntent(launcher, account, messageUri, isReplyAll ? REPLY_ALL : REPLY);
    }

    /**
     * Can be called from a non-UI thread.
     */
    public static Intent createForwardIntent(final Context launcher, final Account account,
            final Uri messageUri) {
        return createActionIntent(launcher, account, messageUri, FORWARD);
    }

    private static Intent createActionIntent(final Context launcher, final Account account,
            final Uri messageUri, final int action) {
        final Intent intent = new Intent(launcher, ComposeActivity.class);

        intent.putExtra(EXTRA_FROM_EMAIL_TASK, true);
        intent.putExtra(EXTRA_ACTION, action);
        intent.putExtra(Utils.EXTRA_ACCOUNT, account);
        intent.putExtra(EXTRA_IN_REFERENCE_TO_MESSAGE_URI, messageUri);

        return intent;
    }

    /**
     * Can be called from a non-UI thread.
     */
    public static void reply(Context launcher, Account account, Message message) {
        launch(launcher, account, message, REPLY);
    }

    /**
     * Can be called from a non-UI thread.
     */
    public static void replyAll(Context launcher, Account account, Message message) {
        launch(launcher, account, message, REPLY_ALL);
    }

    /**
     * Can be called from a non-UI thread.
     */
    public static void forward(Context launcher, Account account, Message message) {
        launch(launcher, account, message, FORWARD);
    }

    private static void launch(Context launcher, Account account, Message message, int action) {
        Intent intent = new Intent(launcher, ComposeActivity.class);
        intent.putExtra(EXTRA_FROM_EMAIL_TASK, true);
        intent.putExtra(EXTRA_ACTION, action);
        intent.putExtra(Utils.EXTRA_ACCOUNT, account);
        if (action == EDIT_DRAFT) {
            intent.putExtra(ORIGINAL_DRAFT_MESSAGE, message);
        } else {
            intent.putExtra(EXTRA_IN_REFERENCE_TO_MESSAGE, message);
        }
        launcher.startActivity(intent);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.compose);
        mSavedInstanceState = savedInstanceState;
        checkValidAccounts();
    }

    private void finishCreate() {
        Bundle savedInstanceState = mSavedInstanceState;
        findViews();
        Intent intent = getIntent();
        Message message;
        ArrayList<AttachmentPreview> previews;
        boolean showQuotedText = false;
        int action;
        // Check for any of the possibly supplied accounts.;
        Account account = null;
        if (hadSavedInstanceStateMessage(savedInstanceState)) {
            action = savedInstanceState.getInt(EXTRA_ACTION, COMPOSE);
            account = savedInstanceState.getParcelable(Utils.EXTRA_ACCOUNT);
            message = (Message) savedInstanceState.getParcelable(EXTRA_MESSAGE);

            previews = savedInstanceState.getParcelableArrayList(EXTRA_ATTACHMENT_PREVIEWS);
            mRefMessage = (Message) savedInstanceState.getParcelable(EXTRA_IN_REFERENCE_TO_MESSAGE);
        } else {
            account = obtainAccount(intent);
            action = intent.getIntExtra(EXTRA_ACTION, COMPOSE);
            // Initialize the message from the message in the intent
            message = (Message) intent.getParcelableExtra(ORIGINAL_DRAFT_MESSAGE);
            previews = intent.getParcelableArrayListExtra(EXTRA_ATTACHMENT_PREVIEWS);
            mRefMessage = (Message) intent.getParcelableExtra(EXTRA_IN_REFERENCE_TO_MESSAGE);
            mRefMessageUri = (Uri) intent.getParcelableExtra(EXTRA_IN_REFERENCE_TO_MESSAGE_URI);
        }
        mAttachmentsView.setAttachmentPreviews(previews);

        setAccount(account);
        if (mAccount == null) {
            return;
        }

        if (intent.getBooleanExtra(EXTRA_FROM_EMAIL_TASK, false)) {
            mLaunchedFromEmail = true;
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            final Uri dataUri = intent.getData();
            if (dataUri != null) {
                final String dataScheme = intent.getData().getScheme();
                final String accountScheme = mAccount.composeIntentUri.getScheme();
                mLaunchedFromEmail = TextUtils.equals(dataScheme, accountScheme);
            }
        }

        if (mRefMessageUri != null) {
            // We have a referenced message that we must look up.
            getLoaderManager().initLoader(REFERENCE_MESSAGE_LOADER, null, this);
            return;
        } else if (message != null && action != EDIT_DRAFT) {
            initFromDraftMessage(message);
            initQuotedTextFromRefMessage(mRefMessage, action);
            showCcBcc(savedInstanceState);
            showQuotedText = message.appendRefMessageContent;
        } else if (action == EDIT_DRAFT) {
            initFromDraftMessage(message);
            boolean showBcc = !TextUtils.isEmpty(message.getBcc());
            boolean showCc = showBcc || !TextUtils.isEmpty(message.getCc());
            mCcBccView.show(false, showCc, showBcc);
            // Update the action to the draft type of the previous draft
            switch (message.draftType) {
                case UIProvider.DraftType.REPLY:
                    action = REPLY;
                    break;
                case UIProvider.DraftType.REPLY_ALL:
                    action = REPLY_ALL;
                    break;
                case UIProvider.DraftType.FORWARD:
                    action = FORWARD;
                    break;
                case UIProvider.DraftType.COMPOSE:
                default:
                    action = COMPOSE;
                    break;
            }
            initQuotedTextFromRefMessage(mRefMessage, action);
            showQuotedText = message.appendRefMessageContent;
        } else if ((action == REPLY || action == REPLY_ALL || action == FORWARD)) {
            if (mRefMessage != null) {
                initFromRefMessage(action);
                showQuotedText = true;
            }
        } else {
            initFromExtras(intent);
        }
        finishSetup(action, intent, savedInstanceState, showQuotedText);
    }

    private void checkValidAccounts() {
        final Account[] allAccounts = AccountUtils.getAccounts(this);
        if (allAccounts == null || allAccounts.length == 0) {
            final Intent noAccountIntent = MailAppProvider.getNoAccountIntent(this);
            if (noAccountIntent != null) {
                mAccounts = null;
                startActivityForResult(noAccountIntent, RESULT_CREATE_ACCOUNT);
            }
        } else {
            // If none of the accounts are syncing, setup a watcher.
            boolean anySyncing = false;
            for (Account a : allAccounts) {
                if (a.isAccountReady()) {
                    anySyncing = true;
                    break;
                }
            }
            if (!anySyncing) {
                // There are accounts, but none are sync'd, which is just like having no accounts.
                mAccounts = null;
                getLoaderManager().initLoader(LOADER_ACCOUNT_CURSOR, null, this);
                return;
            }
            mAccounts = AccountUtils.getSyncingAccounts(this);
            finishCreate();
        }
    }

    private Account obtainAccount(Intent intent) {
        Account account = null;
        Object accountExtra = null;
        if (intent != null && intent.getExtras() != null) {
            accountExtra = intent.getExtras().get(Utils.EXTRA_ACCOUNT);
            if (accountExtra instanceof Account) {
                return (Account) accountExtra;
            } else if (accountExtra instanceof String) {
                // This is the Account attached to the widget compose intent.
                account = Account.newinstance((String)accountExtra);
                if (account != null) {
                    return account;
                }
            }
            accountExtra = intent.getStringExtra(EXTRA_SELECTED_ACCOUNT);
        }
        if (account == null) {
            MailAppProvider provider = MailAppProvider.getInstance();
            String lastAccountUri = provider.getLastSentFromAccount();
            if (TextUtils.isEmpty(lastAccountUri)) {
                lastAccountUri = provider.getLastViewedAccount();
            }
            if (!TextUtils.isEmpty(lastAccountUri)) {
                accountExtra = Uri.parse(lastAccountUri);
            }
        }
        if (mAccounts != null && mAccounts.length > 0) {
            if (accountExtra instanceof String && !TextUtils.isEmpty((String) accountExtra)) {
                // For backwards compatibility, we need to check account
                // names.
                for (Account a : mAccounts) {
                    if (a.name.equals(accountExtra)) {
                        account = a;
                    }
                }
            } else if (accountExtra instanceof Uri) {
                // The uri of the last viewed account is what is stored in
                // the current code base.
                for (Account a : mAccounts) {
                    if (a.uri.equals(accountExtra)) {
                        account = a;
                    }
                }
            }
            if (account == null) {
                account = mAccounts[0];
            }
        }
        return account;
    }

    private void finishSetup(int action, Intent intent, Bundle savedInstanceState,
            boolean showQuotedText) {
        setFocus(action);
        if (action == COMPOSE) {
            mQuotedTextView.setVisibility(View.GONE);
        }
        initRecipients();
        // Don't bother with the intent if we have procured a message from the
        // intent already.
        if (!hadSavedInstanceStateMessage(savedInstanceState)) {
            initAttachmentsFromIntent(intent);
        }
        initActionBar(action);
        initFromSpinner(savedInstanceState != null ? savedInstanceState : intent.getExtras(),
                action);

        // If this is a draft message, the draft account is whatever account was
        // used to open the draft message in Compose.
        if (mDraft != null) {
            mDraftAccount = mReplyFromAccount;
        }

        initChangeListeners();
        updateHideOrShowCcBcc();
        updateHideOrShowQuotedText(showQuotedText);

        mRespondedInline = mSavedInstanceState != null ?
                mSavedInstanceState.getBoolean(EXTRA_RESPONDED_INLINE) : false;
        if (mRespondedInline) {
            mQuotedTextView.setVisibility(View.GONE);
        }
    }

    private boolean hadSavedInstanceStateMessage(Bundle savedInstanceState) {
        return savedInstanceState != null && savedInstanceState.containsKey(EXTRA_MESSAGE);
    }

    private void updateHideOrShowQuotedText(boolean showQuotedText) {
        mQuotedTextView.updateCheckedState(showQuotedText);
        mQuotedTextView.setUpperDividerVisible(mAttachmentsView.getAttachments().size() > 0);
    }

    private void setFocus(int action) {
        if (action == EDIT_DRAFT) {
            int type = mDraft.draftType;
            switch (type) {
                case UIProvider.DraftType.COMPOSE:
                case UIProvider.DraftType.FORWARD:
                    action = COMPOSE;
                    break;
                case UIProvider.DraftType.REPLY:
                case UIProvider.DraftType.REPLY_ALL:
                default:
                    action = REPLY;
                    break;
            }
        }
        switch (action) {
            case FORWARD:
            case COMPOSE:
                if (TextUtils.isEmpty(mTo.getText())) {
                    mTo.requestFocus();
                    break;
                }
            case REPLY:
            case REPLY_ALL:
            default:
                focusBody();
                break;
        }
    }

    /**
     * Focus the body of the message.
     */
    public void focusBody() {
        mBodyView.requestFocus();
        int length = mBodyView.getText().length();

        int signatureStartPos = getSignatureStartPosition(
                mSignature, mBodyView.getText().toString());
        if (signatureStartPos > -1) {
            // In case the user deleted the newlines...
            mBodyView.setSelection(signatureStartPos);
        } else if (length >= 0) {
            // Move cursor to the end.
            mBodyView.setSelection(length);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Update the from spinner as other accounts
        // may now be available.
        if (mFromSpinner != null && mAccount != null) {
            mFromSpinner.asyncInitFromSpinner(mComposeMode, mAccount, mAccounts);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (mSendConfirmDialog != null) {
            mSendConfirmDialog.dismiss();
        }
        if (mRecipientErrorDialog != null) {
            mRecipientErrorDialog.dismiss();
        }
        // When the user exits the compose view, see if this draft needs saving.
        // Don't save unnecessary drafts if we are only changing the orientation.
        if (!isChangingConfigurations()) {
            saveIfNeeded();
        }
    }

    @Override
    protected final void onActivityResult(int request, int result, Intent data) {
        if (request == RESULT_PICK_ATTACHMENT && result == RESULT_OK) {
            addAttachmentAndUpdateView(data);
            mAddingAttachment = false;
        } else if (request == RESULT_CREATE_ACCOUNT) {
                // We were waiting for the user to create an account
            if (result != RESULT_OK) {
                finish();
            } else {
                // Watch for accounts to show up!
                // restart the loader to get the updated list of accounts
                getLoaderManager().initLoader(LOADER_ACCOUNT_CURSOR, null, this);
                showWaitFragment(null);
            }
        }
    }

    @Override
    public final void onRestoreInstanceState(Bundle savedInstanceState) {
        clearChangeListeners();
        super.onRestoreInstanceState(savedInstanceState);
        if (savedInstanceState != null) {
            if (savedInstanceState.containsKey(EXTRA_FOCUS_SELECTION_START)) {
                int selectionStart = savedInstanceState.getInt(EXTRA_FOCUS_SELECTION_START);
                int selectionEnd = savedInstanceState.getInt(EXTRA_FOCUS_SELECTION_END);
                // There should be a focus and it should be an EditText since we
                // only save these extras if these conditions are true.
                EditText focusEditText = (EditText) getCurrentFocus();
                final int length = focusEditText.getText().length();
                if (selectionStart < length && selectionEnd < length) {
                    focusEditText.setSelection(selectionStart, selectionEnd);
                }
            }
        }
        initChangeListeners();
    }

    @Override
    public final void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        // We have no accounts so there is nothing to compose, and therefore, nothing to save.
        if (mAccounts == null || mAccounts.length == 0) {
            return;
        }
        // The framework is happy to save and restore the selection but only if it also saves and
        // restores the contents of the edit text. That's a lot of text to put in a bundle so we do
        // this manually.
        View focus = getCurrentFocus();
        if (focus != null && focus instanceof EditText) {
            EditText focusEditText = (EditText) focus;
            state.putInt(EXTRA_FOCUS_SELECTION_START, focusEditText.getSelectionStart());
            state.putInt(EXTRA_FOCUS_SELECTION_END, focusEditText.getSelectionEnd());
        }

        final List<ReplyFromAccount> replyFromAccounts = mFromSpinner.getReplyFromAccounts();
        final int selectedPos = mFromSpinner.getSelectedItemPosition();
        final ReplyFromAccount selectedReplyFromAccount = (replyFromAccounts != null
                && replyFromAccounts.size() > 0 && replyFromAccounts.size() > selectedPos) ?
                        replyFromAccounts.get(selectedPos) : null;
        if (selectedReplyFromAccount != null) {
            state.putString(EXTRA_SELECTED_REPLY_FROM_ACCOUNT, selectedReplyFromAccount.serialize()
                    .toString());
            state.putParcelable(Utils.EXTRA_ACCOUNT, selectedReplyFromAccount.account);
        } else {
            state.putParcelable(Utils.EXTRA_ACCOUNT, mAccount);
        }

        if (mDraftId == UIProvider.INVALID_MESSAGE_ID && mRequestId !=0) {
            // We don't have a draft id, and we have a request id,
            // save the request id.
            state.putInt(EXTRA_REQUEST_ID, mRequestId);
        }

        // We want to restore the current mode after a pause
        // or rotation.
        int mode = getMode();
        state.putInt(EXTRA_ACTION, mode);

        final Message message = createMessage(selectedReplyFromAccount, mode);
        if (mDraft != null) {
            message.id = mDraft.id;
            message.serverId = mDraft.serverId;
            message.uri = mDraft.uri;
        }
        state.putParcelable(EXTRA_MESSAGE, message);

        if (mRefMessage != null) {
            state.putParcelable(EXTRA_IN_REFERENCE_TO_MESSAGE, mRefMessage);
        }
        state.putBoolean(EXTRA_SHOW_CC, mCcBccView.isCcVisible());
        state.putBoolean(EXTRA_SHOW_BCC, mCcBccView.isBccVisible());
        state.putBoolean(EXTRA_RESPONDED_INLINE, mRespondedInline);
        state.putBoolean(EXTRA_SAVE_ENABLED, mSave != null && mSave.isEnabled());
        state.putParcelableArrayList(
                EXTRA_ATTACHMENT_PREVIEWS, mAttachmentsView.getAttachmentPreviews());
    }

    private int getMode() {
        int mode = ComposeActivity.COMPOSE;
        ActionBar actionBar = getActionBar();
        if (actionBar != null
                && actionBar.getNavigationMode() == ActionBar.NAVIGATION_MODE_LIST) {
            mode = actionBar.getSelectedNavigationIndex();
        }
        return mode;
    }

    private Message createMessage(ReplyFromAccount selectedReplyFromAccount, int mode) {
        Message message = new Message();
        message.id = UIProvider.INVALID_MESSAGE_ID;
        message.serverId = null;
        message.uri = null;
        message.conversationUri = null;
        message.subject = mSubject.getText().toString();
        message.snippet = null;
        message.setTo(formatSenders(mTo.getText().toString()));
        message.setCc(formatSenders(mCc.getText().toString()));
        message.setBcc(formatSenders(mBcc.getText().toString()));
        message.setReplyTo(null);
        message.dateReceivedMs = 0;
        String htmlBody = Html.toHtml(new SpannableString(mBodyView.getText().toString()));
        StringBuilder fullBody = new StringBuilder(htmlBody);
        message.bodyHtml = fullBody.toString();
        message.bodyText = mBodyView.getText().toString();
        message.embedsExternalResources = false;
        message.refMessageId = mRefMessage != null ? mRefMessage.uri.toString() : null;
        message.appendRefMessageContent = mQuotedTextView.getQuotedTextIfIncluded() != null;
        ArrayList<Attachment> attachments = mAttachmentsView.getAttachments();
        message.hasAttachments = attachments != null && attachments.size() > 0;
        message.attachmentListUri = null;
        message.messageFlags = 0;
        message.saveUri = null;
        message.sendUri = null;
        message.alwaysShowImages = false;
        message.attachmentsJson = Attachment.toJSONArray(attachments);
        CharSequence quotedText = mQuotedTextView.getQuotedText();
        message.quotedTextOffset = !TextUtils.isEmpty(quotedText) ? QuotedTextView
                .getQuotedTextOffset(quotedText.toString()) : -1;
        message.accountUri = null;
        message.setFrom(selectedReplyFromAccount != null ? selectedReplyFromAccount.address
                : mAccount != null ? mAccount.name : null);
        message.draftType = getDraftType(mode);
        message.setTo(formatSenders(mTo.getText().toString()));
        message.setCc(formatSenders(mCc.getText().toString()));
        message.setBcc(formatSenders(mBcc.getText().toString()));
        return message;
    }

    private String formatSenders(String string) {
        if (!TextUtils.isEmpty(string) && string.charAt(string.length() - 1) == ',') {
            return string.substring(0, string.length() - 1);
        }
        return string;
    }

    @VisibleForTesting
    void setAccount(Account account) {
        if (account == null) {
            return;
        }
        if (!account.equals(mAccount)) {
            mAccount = account;
            mCachedSettings = mAccount.settings;
            appendSignature();
        }
        if (mAccount != null) {
            MailActivity.setNfcMessage(mAccount.name);
        }
    }

    private void initFromSpinner(Bundle bundle, int action) {
        String accountString = null;
        if (action == EDIT_DRAFT && mDraft.draftType == UIProvider.DraftType.COMPOSE) {
            action = COMPOSE;
        }
        mFromSpinner.asyncInitFromSpinner(action, mAccount, mAccounts);
        if (bundle != null) {
            if (bundle.containsKey(EXTRA_SELECTED_REPLY_FROM_ACCOUNT)) {
                mReplyFromAccount = ReplyFromAccount.deserialize(mAccount,
                        bundle.getString(EXTRA_SELECTED_REPLY_FROM_ACCOUNT));
            } else if (bundle.containsKey(EXTRA_FROM_ACCOUNT_STRING)) {
                accountString = bundle.getString(EXTRA_FROM_ACCOUNT_STRING);
                mReplyFromAccount = mFromSpinner.getMatchingReplyFromAccount(accountString);
            }
        }
        if (mReplyFromAccount == null) {
            if (mDraft != null) {
                mReplyFromAccount = getReplyFromAccountFromDraft(mAccount, mDraft);
            } else if (mRefMessage != null) {
                mReplyFromAccount = getReplyFromAccountForReply(mAccount, mRefMessage);
            }
        }
        if (mReplyFromAccount == null) {
            mReplyFromAccount = getDefaultReplyFromAccount(mAccount);
        }

        mFromSpinner.setCurrentAccount(mReplyFromAccount);

        if (mFromSpinner.getCount() > 1) {
            // If there is only 1 account, just show that account.
            // Otherwise, give the user the ability to choose which account to
            // send mail from / save drafts to.
            mFromStatic.setVisibility(View.GONE);
            mFromStaticText.setText(mAccount.name);
            mFromSpinnerWrapper.setVisibility(View.VISIBLE);
        } else {
            mFromStatic.setVisibility(View.VISIBLE);
            mFromStaticText.setText(mAccount.name);
            mFromSpinnerWrapper.setVisibility(View.GONE);
        }
    }

    private ReplyFromAccount getReplyFromAccountForReply(Account account, Message refMessage) {
        if (refMessage.accountUri != null) {
            // This must be from combined inbox.
            List<ReplyFromAccount> replyFromAccounts = mFromSpinner.getReplyFromAccounts();
            for (ReplyFromAccount from : replyFromAccounts) {
                if (from.account.uri.equals(refMessage.accountUri)) {
                    return from;
                }
            }
            return null;
        } else {
            return getReplyFromAccount(account, refMessage);
        }
    }

    /**
     * Given an account and which email address the message was sent to,
     * return who the message should be sent from.
     * @param account Account in which the message arrived.
     * @param sentTo Email address to which the message was sent.
     * @return the address from which to reply.
     */
    public ReplyFromAccount getReplyFromAccount(Account account, Message refMessage) {
        // First see if we are supposed to use the default address or
        // the address it was sentTo.
        if (mCachedSettings.forceReplyFromDefault) {
            return getDefaultReplyFromAccount(account);
        } else {
            // If we aren't explicitly told which account to look for, look at
            // all the message recipients and find one that matches
            // a custom from or account.
            List<String> allRecipients = new ArrayList<String>();
            allRecipients.addAll(Arrays.asList(refMessage.getToAddresses()));
            allRecipients.addAll(Arrays.asList(refMessage.getCcAddresses()));
            return getMatchingRecipient(account, allRecipients);
        }
    }

    /**
     * Compare all the recipients of an email to the current account and all
     * custom addresses associated with that account. Return the match if there
     * is one, or the default account if there isn't.
     */
    protected ReplyFromAccount getMatchingRecipient(Account account, List<String> sentTo) {
        // Tokenize the list and place in a hashmap.
        ReplyFromAccount matchingReplyFrom = null;
        Rfc822Token[] tokens;
        HashSet<String> recipientsMap = new HashSet<String>();
        for (String address : sentTo) {
            tokens = Rfc822Tokenizer.tokenize(address);
            for (int i = 0; i < tokens.length; i++) {
                recipientsMap.add(tokens[i].getAddress());
            }
        }

        int matchingAddressCount = 0;
        List<ReplyFromAccount> customFroms;
        customFroms = account.getReplyFroms();
        if (customFroms != null) {
            for (ReplyFromAccount entry : customFroms) {
                if (recipientsMap.contains(entry.address)) {
                    matchingReplyFrom = entry;
                    matchingAddressCount++;
                }
            }
        }
        if (matchingAddressCount > 1) {
            matchingReplyFrom = getDefaultReplyFromAccount(account);
        }
        return matchingReplyFrom;
    }

    private ReplyFromAccount getDefaultReplyFromAccount(Account account) {
        for (ReplyFromAccount from : account.getReplyFroms()) {
            if (from.isDefault) {
                return from;
            }
        }
        return new ReplyFromAccount(account, account.uri, account.name, account.name, account.name,
                true, false);
    }

    private ReplyFromAccount getReplyFromAccountFromDraft(Account account, Message msg) {
        String sender = msg.getFrom();
        ReplyFromAccount replyFromAccount = null;
        List<ReplyFromAccount> replyFromAccounts = mFromSpinner.getReplyFromAccounts();
        if (TextUtils.equals(account.name, sender)) {
            replyFromAccount = new ReplyFromAccount(mAccount, mAccount.uri, mAccount.name,
                    mAccount.name, mAccount.name, true, false);
        } else {
            for (ReplyFromAccount fromAccount : replyFromAccounts) {
                if (TextUtils.equals(fromAccount.name, sender)) {
                    replyFromAccount = fromAccount;
                    break;
                }
            }
        }
        return replyFromAccount;
    }

    private void findViews() {
        findViewById(R.id.compose).setVisibility(View.VISIBLE);
        mCcBccButton = (Button) findViewById(R.id.add_cc_bcc);
        if (mCcBccButton != null) {
            mCcBccButton.setOnClickListener(this);
        }
        mCcBccView = (CcBccView) findViewById(R.id.cc_bcc_wrapper);
        mAttachmentsView = (AttachmentsView)findViewById(R.id.attachments);
        mPhotoAttachmentsButton = findViewById(R.id.add_photo_attachment);
        if (mPhotoAttachmentsButton != null) {
            mPhotoAttachmentsButton.setOnClickListener(this);
        }
        mVideoAttachmentsButton = findViewById(R.id.add_video_attachment);
        if (mVideoAttachmentsButton != null) {
            mVideoAttachmentsButton.setOnClickListener(this);
        }
        mTo = (RecipientEditTextView) findViewById(R.id.to);
        mCc = (RecipientEditTextView) findViewById(R.id.cc);
        mBcc = (RecipientEditTextView) findViewById(R.id.bcc);
        // TODO: add special chips text change watchers before adding
        // this as a text changed watcher to the to, cc, bcc fields.
        mSubject = (TextView) findViewById(R.id.subject);
        mSubject.setOnEditorActionListener(this);
        mQuotedTextView = (QuotedTextView) findViewById(R.id.quoted_text_view);
        mQuotedTextView.setRespondInlineListener(this);
        mBodyView = (EditText) findViewById(R.id.body);
        mFromStatic = findViewById(R.id.static_from_content);
        mFromStaticText = (TextView) findViewById(R.id.from_account_name);
        mFromSpinnerWrapper = findViewById(R.id.spinner_from_content);
        mFromSpinner = (FromAddressSpinner) findViewById(R.id.from_picker);
    }

    @Override
    public boolean onEditorAction(TextView view, int action, KeyEvent keyEvent) {
        if (action == EditorInfo.IME_ACTION_DONE) {
            focusBody();
            return true;
        }
        return false;
    }

    protected TextView getBody() {
        return mBodyView;
    }

    @VisibleForTesting
    public Account getFromAccount() {
        return mReplyFromAccount != null && mReplyFromAccount.account != null ?
                mReplyFromAccount.account : mAccount;
    }

    private void clearChangeListeners() {
        mSubject.removeTextChangedListener(this);
        mBodyView.removeTextChangedListener(this);
        mTo.removeTextChangedListener(mToListener);
        mCc.removeTextChangedListener(mCcListener);
        mBcc.removeTextChangedListener(mBccListener);
        mFromSpinner.setOnAccountChangedListener(null);
        mAttachmentsView.setAttachmentChangesListener(null);
    }

    // Now that the message has been initialized from any existing draft or
    // ref message data, set up listeners for any changes that occur to the
    // message.
    private void initChangeListeners() {
        // Make sure we only add text changed listeners once!
        clearChangeListeners();
        mSubject.addTextChangedListener(this);
        mBodyView.addTextChangedListener(this);
        if (mToListener == null) {
            mToListener = new RecipientTextWatcher(mTo, this);
        }
        mTo.addTextChangedListener(mToListener);
        if (mCcListener == null) {
            mCcListener = new RecipientTextWatcher(mCc, this);
        }
        mCc.addTextChangedListener(mCcListener);
        if (mBccListener == null) {
            mBccListener = new RecipientTextWatcher(mBcc, this);
        }
        mBcc.addTextChangedListener(mBccListener);
        mFromSpinner.setOnAccountChangedListener(this);
        mAttachmentsView.setAttachmentChangesListener(this);
    }

    private void initActionBar(int action) {
        mComposeMode = action;
        ActionBar actionBar = getActionBar();
        if (actionBar == null) {
            return;
        }
        if (action == ComposeActivity.COMPOSE) {
            actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_STANDARD);
            actionBar.setTitle(R.string.compose);
        } else {
            actionBar.setTitle(null);
            if (mComposeModeAdapter == null) {
                mComposeModeAdapter = new ComposeModeAdapter(this);
            }
            actionBar.setNavigationMode(ActionBar.NAVIGATION_MODE_LIST);
            actionBar.setListNavigationCallbacks(mComposeModeAdapter, this);
            switch (action) {
                case ComposeActivity.REPLY:
                    actionBar.setSelectedNavigationItem(0);
                    break;
                case ComposeActivity.REPLY_ALL:
                    actionBar.setSelectedNavigationItem(1);
                    break;
                case ComposeActivity.FORWARD:
                    actionBar.setSelectedNavigationItem(2);
                    break;
            }
        }
        actionBar.setDisplayOptions(ActionBar.DISPLAY_HOME_AS_UP | ActionBar.DISPLAY_SHOW_HOME,
                ActionBar.DISPLAY_HOME_AS_UP | ActionBar.DISPLAY_SHOW_HOME);
        actionBar.setHomeButtonEnabled(true);
    }

    private void initFromRefMessage(int action) {
        setFieldsFromRefMessage(action);
        if (mRefMessage != null) {
            // CC field only gets populated when doing REPLY_ALL.
            // BCC never gets auto-populated, unless the user is editing
            // a draft with one.
            if (!TextUtils.isEmpty(mCc.getText()) && action == REPLY_ALL) {
                mCcBccView.show(false, true, false);
            }
        }
        updateHideOrShowCcBcc();
    }

    private void setFieldsFromRefMessage(int action) {
        setSubject(mRefMessage, action);
        // Setup recipients
        if (action == FORWARD) {
            mForward = true;
        }
        initRecipientsFromRefMessage(mRefMessage, action);
        initQuotedTextFromRefMessage(mRefMessage, action);
        if (action == ComposeActivity.FORWARD || mAttachmentsChanged) {
            initAttachments(mRefMessage);
        }
    }

    private void initFromDraftMessage(Message message) {
        LogUtils.d(LOG_TAG, "Intializing draft from previous draft message: %s", message);

        mDraft = message;
        mDraftId = message.id;
        mSubject.setText(message.subject);
        mForward = message.draftType == UIProvider.DraftType.FORWARD;
        final List<String> toAddresses = Arrays.asList(message.getToAddresses());
        addToAddresses(toAddresses);
        addCcAddresses(Arrays.asList(message.getCcAddresses()), toAddresses);
        addBccAddresses(Arrays.asList(message.getBccAddresses()));
        if (message.hasAttachments) {
            List<Attachment> attachments = message.getAttachments();
            for (Attachment a : attachments) {
                addAttachmentAndUpdateView(a);
            }
        }
        int quotedTextIndex = message.appendRefMessageContent ?
                message.quotedTextOffset : -1;
        // Set the body
        CharSequence quotedText = null;
        if (!TextUtils.isEmpty(message.bodyHtml)) {
            CharSequence htmlText = "";
            if (quotedTextIndex > -1) {
                // Find the offset in the htmltext of the actual quoted text and strip it out.
                quotedTextIndex = QuotedTextView.findQuotedTextIndex(message.bodyHtml);
                if (quotedTextIndex > -1) {
                    htmlText = Utils.convertHtmlToPlainText(message.bodyHtml.substring(0,
                            quotedTextIndex));
                    quotedText = message.bodyHtml.subSequence(quotedTextIndex,
                            message.bodyHtml.length());
                }
            } else {
                htmlText = Utils.convertHtmlToPlainText(message.bodyHtml);
            }
            mBodyView.setText(htmlText);
        } else {
            final String body = message.bodyText;
            final CharSequence bodyText = !TextUtils.isEmpty(body) ?
                    (quotedTextIndex > -1 ?
                            message.bodyText.substring(0, quotedTextIndex) : message.bodyText)
                            : "";
            if (quotedTextIndex > -1) {
                quotedText = !TextUtils.isEmpty(body) ? message.bodyText.substring(quotedTextIndex)
                        : null;
            }
            mBodyView.setText(bodyText);
        }
        if (quotedTextIndex > -1 && quotedText != null) {
            mQuotedTextView.setQuotedTextFromDraft(quotedText, mForward);
        }
    }

    /**
     * Fill all the widgets with the content found in the Intent Extra, if any.
     * Also apply the same style to all widgets. Note: if initFromExtras is
     * called as a result of switching between reply, reply all, and forward per
     * the latest revision of Gmail, and the user has already made changes to
     * attachments on a previous incarnation of the message (as a reply, reply
     * all, or forward), the original attachments from the message will not be
     * re-instantiated. The user's changes will be respected. This follows the
     * web gmail interaction.
     */
    public void initFromExtras(Intent intent) {
        // If we were invoked with a SENDTO intent, the value
        // should take precedence
        final Uri dataUri = intent.getData();
        if (dataUri != null) {
            if (MAIL_TO.equals(dataUri.getScheme())) {
                initFromMailTo(dataUri.toString());
            } else {
                if (!mAccount.composeIntentUri.equals(dataUri)) {
                    String toText = dataUri.getSchemeSpecificPart();
                    if (toText != null) {
                        mTo.setText("");
                        addToAddresses(Arrays.asList(TextUtils.split(toText, ",")));
                    }
                }
            }
        }

        String[] extraStrings = intent.getStringArrayExtra(Intent.EXTRA_EMAIL);
        if (extraStrings != null) {
            addToAddresses(Arrays.asList(extraStrings));
        }
        extraStrings = intent.getStringArrayExtra(Intent.EXTRA_CC);
        if (extraStrings != null) {
            addCcAddresses(Arrays.asList(extraStrings), null);
        }
        extraStrings = intent.getStringArrayExtra(Intent.EXTRA_BCC);
        if (extraStrings != null) {
            addBccAddresses(Arrays.asList(extraStrings));
        }

        String extraString = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        if (extraString != null) {
            mSubject.setText(extraString);
        }

        for (String extra : ALL_EXTRAS) {
            if (intent.hasExtra(extra)) {
                String value = intent.getStringExtra(extra);
                if (EXTRA_TO.equals(extra)) {
                    addToAddresses(Arrays.asList(TextUtils.split(value, ",")));
                } else if (EXTRA_CC.equals(extra)) {
                    addCcAddresses(Arrays.asList(TextUtils.split(value, ",")), null);
                } else if (EXTRA_BCC.equals(extra)) {
                    addBccAddresses(Arrays.asList(TextUtils.split(value, ",")));
                } else if (EXTRA_SUBJECT.equals(extra)) {
                    mSubject.setText(value);
                } else if (EXTRA_BODY.equals(extra)) {
                    setBody(value, true /* with signature */);
                }
            }
        }

        Bundle extras = intent.getExtras();
        if (extras != null) {
            CharSequence text = extras.getCharSequence(Intent.EXTRA_TEXT);
            if (text != null) {
                setBody(text, true /* with signature */);
            }
        }
    }

    @VisibleForTesting
    protected String decodeEmailInUri(String s) throws UnsupportedEncodingException {
        // TODO: handle the case where there are spaces in the display name as
        // well as the email such as "Guy with spaces <guy+with+spaces@gmail.com>"
        // as they could be encoded ambiguously.
        // Since URLDecode.decode changes + into ' ', and + is a valid
        // email character, we need to find/ replace these ourselves before
        // decoding.
        String replacePlus = s.replace("+", "%2B");
        try {
            return URLDecoder.decode(replacePlus, UTF8_ENCODING_NAME);
        } catch (IllegalArgumentException e) {
            if (LogUtils.isLoggable(LOG_TAG, LogUtils.VERBOSE)) {
                LogUtils.e(LOG_TAG, "%s while decoding '%s'", e.getMessage(), s);
            } else {
                LogUtils.e(LOG_TAG, e, "Exception  while decoding mailto address");
            }
            return null;
        }
    }

    /**
     * Initialize the compose view from a String representing a mailTo uri.
     * @param mailToString The uri as a string.
     */
    public void initFromMailTo(String mailToString) {
        // We need to disguise this string as a URI in order to parse it
        // TODO:  Remove this hack when http://b/issue?id=1445295 gets fixed
        Uri uri = Uri.parse("foo://" + mailToString);
        int index = mailToString.indexOf("?");
        int length = "mailto".length() + 1;
        String to;
        try {
            // Extract the recipient after mailto:
            if (index == -1) {
                to = decodeEmailInUri(mailToString.substring(length));
            } else {
                to = decodeEmailInUri(mailToString.substring(length, index));
            }
            if (!TextUtils.isEmpty(to)) {
                addToAddresses(Arrays.asList(TextUtils.split(to, ",")));
            }
        } catch (UnsupportedEncodingException e) {
            if (LogUtils.isLoggable(LOG_TAG, LogUtils.VERBOSE)) {
                LogUtils.e(LOG_TAG, "%s while decoding '%s'", e.getMessage(), mailToString);
            } else {
                LogUtils.e(LOG_TAG, e, "Exception  while decoding mailto address");
            }
        }

        List<String> cc = uri.getQueryParameters("cc");
        addCcAddresses(Arrays.asList(cc.toArray(new String[cc.size()])), null);

        List<String> otherTo = uri.getQueryParameters("to");
        addToAddresses(Arrays.asList(otherTo.toArray(new String[otherTo.size()])));

        List<String> bcc = uri.getQueryParameters("bcc");
        addBccAddresses(Arrays.asList(bcc.toArray(new String[bcc.size()])));

        List<String> subject = uri.getQueryParameters("subject");
        if (subject.size() > 0) {
            try {
                mSubject.setText(URLDecoder.decode(subject.get(0), UTF8_ENCODING_NAME));
            } catch (UnsupportedEncodingException e) {
                LogUtils.e(LOG_TAG, "%s while decoding subject '%s'",
                        e.getMessage(), subject);
            }
        }

        List<String> body = uri.getQueryParameters("body");
        if (body.size() > 0) {
            try {
                setBody(URLDecoder.decode(body.get(0), UTF8_ENCODING_NAME),
                        true /* with signature */);
            } catch (UnsupportedEncodingException e) {
                LogUtils.e(LOG_TAG, "%s while decoding body '%s'", e.getMessage(), body);
            }
        }
    }

    @VisibleForTesting
    protected void initAttachments(Message refMessage) {
        addAttachments(refMessage.getAttachments());
    }

    public long addAttachments(List<Attachment> attachments) {
        long size = 0;
        AttachmentFailureException error = null;
        for (Attachment a : attachments) {
            try {
                size += mAttachmentsView.addAttachment(mAccount, a);
            } catch (AttachmentFailureException e) {
                error = e;
            }
        }
        if (error != null) {
            LogUtils.e(LOG_TAG, error, "Error adding attachment");
            if (attachments.size() > 1) {
                showAttachmentTooBigToast(R.string.too_large_to_attach_multiple);
            } else {
                showAttachmentTooBigToast(error.getErrorRes());
            }
        }
        return size;
    }

    /**
     * When an attachment is too large to be added to a message, show a toast.
     * This method also updates the position of the toast so that it is shown
     * clearly above they keyboard if it happens to be open.
     */
    private void showAttachmentTooBigToast(int errorRes) {
        String maxSize = AttachmentUtils.convertToHumanReadableSize(
                getApplicationContext(), mAccount.settings.getMaxAttachmentSize());
        showErrorToast(getString(errorRes, maxSize));
    }

    private void showErrorToast(String message) {
        Toast t = Toast.makeText(this, message, Toast.LENGTH_LONG);
        t.setText(message);
        t.setGravity(Gravity.CENTER_HORIZONTAL, 0,
                getResources().getDimensionPixelSize(R.dimen.attachment_toast_yoffset));
        t.show();
    }

    private void initAttachmentsFromIntent(Intent intent) {
        Bundle extras = intent.getExtras();
        if (extras == null) {
            extras = Bundle.EMPTY;
        }
        final String action = intent.getAction();
        if (!mAttachmentsChanged) {
            long totalSize = 0;
            if (extras.containsKey(EXTRA_ATTACHMENTS)) {
                String[] uris = (String[]) extras.getSerializable(EXTRA_ATTACHMENTS);
                for (String uriString : uris) {
                    final Uri uri = Uri.parse(uriString);
                    long size = 0;
                    try {
                        size =  mAttachmentsView.addAttachment(mAccount, uri);
                    } catch (AttachmentFailureException e) {
                        LogUtils.e(LOG_TAG, e, "Error adding attachment");
                        showAttachmentTooBigToast(e.getErrorRes());
                    }
                    totalSize += size;
                }
            }
            if (extras.containsKey(Intent.EXTRA_STREAM)) {
                if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
                    ArrayList<Parcelable> uris = extras
                            .getParcelableArrayList(Intent.EXTRA_STREAM);
                    ArrayList<Attachment> attachments = new ArrayList<Attachment>();
                    for (Parcelable uri : uris) {
                        try {
                            attachments.add(mAttachmentsView.generateLocalAttachment((Uri) uri));
                        } catch (AttachmentFailureException e) {
                            LogUtils.e(LOG_TAG, e, "Error adding attachment");
                            String maxSize = AttachmentUtils.convertToHumanReadableSize(
                                    getApplicationContext(),
                                    mAccount.settings.getMaxAttachmentSize());
                            showErrorToast(getString
                                    (R.string.generic_attachment_problem, maxSize));
                        }
                    }
                    totalSize += addAttachments(attachments);
                } else {
                    final Uri uri = (Uri) extras.getParcelable(Intent.EXTRA_STREAM);
                    long size = 0;
                    try {
                        size = mAttachmentsView.addAttachment(mAccount, uri);
                    } catch (AttachmentFailureException e) {
                        LogUtils.e(LOG_TAG, e, "Error adding attachment");
                        showAttachmentTooBigToast(e.getErrorRes());
                    }
                    totalSize += size;
                }
            }

            if (totalSize > 0) {
                mAttachmentsChanged = true;
                updateSaveUi();
            }
        }
    }


    private void initQuotedTextFromRefMessage(Message refMessage, int action) {
        if (mRefMessage != null && (action == REPLY || action == REPLY_ALL || action == FORWARD)) {
            mQuotedTextView.setQuotedText(action, refMessage, action != FORWARD);
        }
    }

    private void updateHideOrShowCcBcc() {
        // Its possible there is a menu item OR a button.
        boolean ccVisible = mCcBccView.isCcVisible();
        boolean bccVisible = mCcBccView.isBccVisible();
        if (mCcBccButton != null) {
            if (!ccVisible || !bccVisible) {
                mCcBccButton.setVisibility(View.VISIBLE);
                mCcBccButton.setText(getString(!ccVisible ? R.string.add_cc_label
                        : R.string.add_bcc_label));
            } else {
                mCcBccButton.setVisibility(View.INVISIBLE);
            }
        }
    }

    private void showCcBcc(Bundle state) {
        if (state != null && state.containsKey(EXTRA_SHOW_CC)) {
            boolean showCc = state.getBoolean(EXTRA_SHOW_CC);
            boolean showBcc = state.getBoolean(EXTRA_SHOW_BCC);
            if (showCc || showBcc) {
                mCcBccView.show(false, showCc, showBcc);
            }
        }
    }

    /**
     * Add attachment and update the compose area appropriately.
     * @param data
     */
    public void addAttachmentAndUpdateView(Intent data) {
        addAttachmentAndUpdateView(data != null ? data.getData() : (Uri) null);
    }

    public void addAttachmentAndUpdateView(Uri contentUri) {
        if (contentUri == null) {
            return;
        }
        try {
            addAttachmentAndUpdateView(mAttachmentsView.generateLocalAttachment(contentUri));
        } catch (AttachmentFailureException e) {
            LogUtils.e(LOG_TAG, e, "Error adding attachment");
            showErrorToast(getResources().getString(
                    e.getErrorRes(),
                    AttachmentUtils.convertToHumanReadableSize(
                            getApplicationContext(), mAccount.settings.getMaxAttachmentSize())));
        }
    }

    public void addAttachmentAndUpdateView(Attachment attachment) {
        try {
            long size = mAttachmentsView.addAttachment(mAccount, attachment);
            if (size > 0) {
                mAttachmentsChanged = true;
                updateSaveUi();
            }
        } catch (AttachmentFailureException e) {
            LogUtils.e(LOG_TAG, e, "Error adding attachment");
            showAttachmentTooBigToast(e.getErrorRes());
        }
    }

    void initRecipientsFromRefMessage(Message refMessage, int action) {
        // Don't populate the address if this is a forward.
        if (action == ComposeActivity.FORWARD) {
            return;
        }
        initReplyRecipients(mAccount.name, refMessage, action);
    }

    @VisibleForTesting
    void initReplyRecipients(String account, Message refMessage, int action) {
        // This is the email address of the current user, i.e. the one composing
        // the reply.
        final String accountEmail = Address.getEmailAddress(account).getAddress();
        String[] sentToAddresses = refMessage.getToAddresses();
        final Collection<String> toAddresses;
        String replytoAddress = refMessage.getReplyTo();
        // If there is no reply to address, the reply to address is the sender.
        if (TextUtils.isEmpty(replytoAddress)) {
            replytoAddress = refMessage.getFrom();
        }

        // If this is a reply, the Cc list is empty. If this is a reply-all, the
        // Cc list is the union of the To and Cc recipients of the original
        // message, excluding the current user's email address and any addresses
        // already on the To list.
        if (action == ComposeActivity.REPLY) {
            toAddresses = initToRecipients(accountEmail, refMessage.getFrom(),
                    replytoAddress, sentToAddresses);
            addToAddresses(toAddresses);
        } else if (action == ComposeActivity.REPLY_ALL) {
            final Set<String> ccAddresses = Sets.newHashSet();
            toAddresses = initToRecipients(accountEmail, refMessage.getFrom(),
                    replytoAddress, sentToAddresses);
            addToAddresses(toAddresses);
            addRecipients(accountEmail, ccAddresses, sentToAddresses);
            addRecipients(accountEmail, ccAddresses, refMessage.getCcAddresses());
            addCcAddresses(ccAddresses, toAddresses);
        }
    }

    private void addToAddresses(Collection<String> addresses) {
        addAddressesToList(addresses, mTo);
    }

    private void addCcAddresses(Collection<String> addresses, Collection<String> toAddresses) {
        addCcAddressesToList(tokenizeAddressList(addresses),
                toAddresses != null ? tokenizeAddressList(toAddresses) : null, mCc);
    }

    private void addBccAddresses(Collection<String> addresses) {
        addAddressesToList(addresses, mBcc);
    }

    @VisibleForTesting
    protected void addCcAddressesToList(List<Rfc822Token[]> addresses,
            List<Rfc822Token[]> compareToList, RecipientEditTextView list) {
        String address;

        if (compareToList == null) {
            for (Rfc822Token[] tokens : addresses) {
                for (int i = 0; i < tokens.length; i++) {
                    address = tokens[i].toString();
                    list.append(address + END_TOKEN);
                }
            }
        } else {
            HashSet<String> compareTo = convertToHashSet(compareToList);
            for (Rfc822Token[] tokens : addresses) {
                for (int i = 0; i < tokens.length; i++) {
                    address = tokens[i].toString();
                    // Check if this is a duplicate:
                    if (!compareTo.contains(tokens[i].getAddress())) {
                        // Get the address here
                        list.append(address + END_TOKEN);
                    }
                }
            }
        }
    }

    private HashSet<String> convertToHashSet(List<Rfc822Token[]> list) {
        HashSet<String> hash = new HashSet<String>();
        for (Rfc822Token[] tokens : list) {
            for (int i = 0; i < tokens.length; i++) {
                hash.add(tokens[i].getAddress());
            }
        }
        return hash;
    }

    protected List<Rfc822Token[]> tokenizeAddressList(Collection<String> addresses) {
        @VisibleForTesting
        List<Rfc822Token[]> tokenized = new ArrayList<Rfc822Token[]>();

        for (String address: addresses) {
            tokenized.add(Rfc822Tokenizer.tokenize(address));
        }
        return tokenized;
    }

    @VisibleForTesting
    void addAddressesToList(Collection<String> addresses, RecipientEditTextView list) {
        for (String address : addresses) {
            addAddressToList(address, list);
        }
    }

    private void addAddressToList(String address, RecipientEditTextView list) {
        if (address == null || list == null)
            return;

        Rfc822Token[] tokens = Rfc822Tokenizer.tokenize(address);

        for (int i = 0; i < tokens.length; i++) {
            list.append(tokens[i] + END_TOKEN);
        }
    }

    @VisibleForTesting
    protected Collection<String> initToRecipients(String accountEmail, String fullSenderAddress,
            String replyToAddress, String[] inToAddresses) {
        // The To recipient is the reply-to address specified in the original
        // message, unless it is:
        // the current user OR a custom from of the current user, in which case
        // it's the To recipient list of the original message.
        // OR missing, in which case use the sender of the original message
        Set<String> toAddresses = Sets.newHashSet();
        if (!TextUtils.isEmpty(replyToAddress) && !recipientMatchesThisAccount(replyToAddress)) {
            toAddresses.add(replyToAddress);
        } else {
            // In this case, the user is replying to a message in which their
            // current account or one of their custom from addresses is the only
            // recipient and they sent the original message.
            if (inToAddresses.length == 1 && recipientMatchesThisAccount(fullSenderAddress)
                    && recipientMatchesThisAccount(inToAddresses[0])) {
                toAddresses.add(inToAddresses[0]);
                return toAddresses;
            }
            // This happens if the user replies to a message they originally
            // wrote. In this case, "reply" really means "re-send," so we
            // target the original recipients. This works as expected even
            // if the user sent the original message to themselves.
            for (String address : inToAddresses) {
                if (!recipientMatchesThisAccount(address)) {
                    toAddresses.add(address);
                }
            }
        }
        return toAddresses;
    }

    private void addRecipients(String accountAddress, Set<String> recipients, String[] addresses) {
        for (String email : addresses) {
            // Do not add this account, or any of its custom from addresses, to
            // the list of recipients.
            final String recipientAddress = Address.getEmailAddress(email).getAddress();
            if (!recipientMatchesThisAccount(recipientAddress)) {
                recipients.add(email.replace("\"\"", ""));
            }
        }
    }

    /**
     * A recipient matches this account if it has the same address as the
     * currently selected account OR one of the custom from addresses associated
     * with the currently selected account.
     * @param accountAddress currently selected account
     * @param recipientAddress address we are comparing with the currently selected account
     * @return
     */
    protected boolean recipientMatchesThisAccount(String recipientAddress) {
        return ReplyFromAccount.matchesAccountOrCustomFrom(mAccount, recipientAddress,
                        mAccount.getReplyFroms());
    }

    private void setSubject(Message refMessage, int action) {
        String subject = refMessage.subject;
        String prefix;
        String correctedSubject = null;
        if (action == ComposeActivity.COMPOSE) {
            prefix = "";
        } else if (action == ComposeActivity.FORWARD) {
            prefix = getString(R.string.forward_subject_label);
        } else {
            prefix = getString(R.string.reply_subject_label);
        }

        // Don't duplicate the prefix
        if (!TextUtils.isEmpty(subject)
                && subject.toLowerCase().startsWith(prefix.toLowerCase())) {
            correctedSubject = subject;
        } else {
            correctedSubject = String
                    .format(getString(R.string.formatted_subject), prefix, subject);
        }
        mSubject.setText(correctedSubject);
    }

    private void initRecipients() {
        setupRecipients(mTo);
        setupRecipients(mCc);
        setupRecipients(mBcc);
    }

    private void setupRecipients(RecipientEditTextView view) {
        view.setAdapter(new RecipientAdapter(this, mAccount));
        view.setTokenizer(new Rfc822Tokenizer());
        if (mValidator == null) {
            final String accountName = mAccount.name;
            int offset = accountName.indexOf("@") + 1;
            String account = accountName;
            if (offset > -1) {
                account = account.substring(accountName.indexOf("@") + 1);
            }
            mValidator = new Rfc822Validator(account);
        }
        view.setValidator(mValidator);
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        switch (id) {
            case R.id.add_cc_bcc:
                // Verify that cc/ bcc aren't showing.
                // Animate in cc/bcc.
                showCcBccViews();
                break;
            case R.id.add_photo_attachment:
                doAttach(MIME_TYPE_PHOTO);
                break;
            case R.id.add_video_attachment:
                doAttach(MIME_TYPE_VIDEO);
                break;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        // Don't render any menu items when there are no accounts.
        if (mAccounts == null || mAccounts.length == 0) {
            return true;
        }
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.compose_menu, menu);

        /*
         * Start save in the correct enabled state.
         * 1) If a user launches compose from within gmail, save is disabled
         * until they add something, at which point, save is enabled, auto save
         * on exit; if the user empties everything, save is disabled, exiting does not
         * auto-save
         * 2) if a user replies/ reply all/ forwards from within gmail, save is
         * disabled until they change something, at which point, save is
         * enabled, auto save on exit; if the user empties everything, save is
         * disabled, exiting does not auto-save.
         * 3) If a user launches compose from another application and something
         * gets populated (attachments, recipients, body, subject, etc), save is
         * enabled, auto save on exit; if the user empties everything, save is
         * disabled, exiting does not auto-save
         */
        mSave = menu.findItem(R.id.save);
        String action = getIntent() != null ? getIntent().getAction() : null;
        enableSave(mSavedInstanceState != null ?
                mSavedInstanceState.getBoolean(EXTRA_SAVE_ENABLED)
                    : (Intent.ACTION_SEND.equals(action)
                            || Intent.ACTION_SEND_MULTIPLE.equals(action)
                            || Intent.ACTION_SENDTO.equals(action)
                            || shouldSave()));

        mSend = menu.findItem(R.id.send);
        MenuItem helpItem = menu.findItem(R.id.help_info_menu_item);
        MenuItem sendFeedbackItem = menu.findItem(R.id.feedback_menu_item);
        if (helpItem != null) {
            helpItem.setVisible(mAccount != null
                    && mAccount.supportsCapability(AccountCapabilities.HELP_CONTENT));
        }
        if (sendFeedbackItem != null) {
            sendFeedbackItem.setVisible(mAccount != null
                    && mAccount.supportsCapability(AccountCapabilities.SEND_FEEDBACK));
        }
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem ccBcc = menu.findItem(R.id.add_cc_bcc);
        if (ccBcc != null && mCc != null) {
            // Its possible there is a menu item OR a button.
            boolean ccFieldVisible = mCc.isShown();
            boolean bccFieldVisible = mBcc.isShown();
            if (!ccFieldVisible || !bccFieldVisible) {
                ccBcc.setVisible(true);
                ccBcc.setTitle(getString(!ccFieldVisible ? R.string.add_cc_label
                        : R.string.add_bcc_label));
            } else {
                ccBcc.setVisible(false);
            }
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        boolean handled = true;
        switch (id) {
            case R.id.add_photo_attachment:
                doAttach(MIME_TYPE_PHOTO);
                break;
            case R.id.add_video_attachment:
                doAttach(MIME_TYPE_VIDEO);
                break;
            case R.id.add_cc_bcc:
                showCcBccViews();
                break;
            case R.id.save:
                doSave(true);
                break;
            case R.id.send:
                doSend();
                break;
            case R.id.discard:
                doDiscard();
                break;
            case R.id.settings:
                Utils.showSettings(this, mAccount);
                break;
            case android.R.id.home:
                onAppUpPressed();
                break;
            case R.id.help_info_menu_item:
                Utils.showHelp(this, mAccount, getString(R.string.compose_help_context));
                break;
            case R.id.feedback_menu_item:
                Utils.sendFeedback(this, mAccount, false);
                break;
            default:
                handled = false;
                break;
        }
        return !handled ? super.onOptionsItemSelected(item) : handled;
    }

    @Override
    public void onBackPressed() {
        // If we are showing the wait fragment, just exit.
        if (getWaitFragment() != null) {
            finish();
        } else {
            super.onBackPressed();
        }
    }

    /**
     * Carries out the "up" action in the action bar.
     */
    private void onAppUpPressed() {
        if (mLaunchedFromEmail) {
            // If this was started from Gmail, simply treat app up as the system back button, so
            // that the last view is restored.
            onBackPressed();
            return;
        }

        // Fire the main activity to ensure it launches the "top" screen of mail.
        // Since the main Activity is singleTask, it should revive that task if it was already
        // started.
        final Intent mailIntent = Utils.createViewInboxIntent(mAccount);
        mailIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK |
                Intent.FLAG_ACTIVITY_TASK_ON_HOME);
        startActivity(mailIntent);
        finish();
    }

    private void doSend() {
        sendOrSaveWithSanityChecks(false, true, false, false);
    }

    private void doSave(boolean showToast) {
        sendOrSaveWithSanityChecks(true, showToast, false, false);
    }

    @VisibleForTesting
    public interface SendOrSaveCallback {
        public void initializeSendOrSave(SendOrSaveTask sendOrSaveTask);
        public void notifyMessageIdAllocated(SendOrSaveMessage sendOrSaveMessage, Message message);
        public Message getMessage();
        public void sendOrSaveFinished(SendOrSaveTask sendOrSaveTask, boolean success);
    }

    @VisibleForTesting
    public static class SendOrSaveTask implements Runnable {
        private final Context mContext;
        @VisibleForTesting
        public final SendOrSaveCallback mSendOrSaveCallback;
        @VisibleForTesting
        public final SendOrSaveMessage mSendOrSaveMessage;
        private ReplyFromAccount mExistingDraftAccount;

        public SendOrSaveTask(Context context, SendOrSaveMessage message,
                SendOrSaveCallback callback, ReplyFromAccount draftAccount) {
            mContext = context;
            mSendOrSaveCallback = callback;
            mSendOrSaveMessage = message;
            mExistingDraftAccount = draftAccount;
        }

        @Override
        public void run() {
            final SendOrSaveMessage sendOrSaveMessage = mSendOrSaveMessage;

            final ReplyFromAccount selectedAccount = sendOrSaveMessage.mAccount;
            Message message = mSendOrSaveCallback.getMessage();
            long messageId = message != null ? message.id : UIProvider.INVALID_MESSAGE_ID;
            // If a previous draft has been saved, in an account that is different
            // than what the user wants to send from, remove the old draft, and treat this
            // as a new message
            if (mExistingDraftAccount != null
                    && !selectedAccount.account.uri.equals(mExistingDraftAccount.account.uri)) {
                if (messageId != UIProvider.INVALID_MESSAGE_ID) {
                    ContentResolver resolver = mContext.getContentResolver();
                    ContentValues values = new ContentValues();
                    values.put(BaseColumns._ID, messageId);
                    if (mExistingDraftAccount.account.expungeMessageUri != null) {
                        new ContentProviderTask.UpdateTask()
                                .run(resolver, mExistingDraftAccount.account.expungeMessageUri,
                                        values, null, null);
                    } else {
                        // TODO(mindyp) delete the conversation.
                    }
                    // reset messageId to 0, so a new message will be created
                    messageId = UIProvider.INVALID_MESSAGE_ID;
                }
            }

            final long messageIdToSave = messageId;
            sendOrSaveMessage(messageIdToSave, sendOrSaveMessage, selectedAccount, message);

            if (!sendOrSaveMessage.mSave) {
                UIProvider.incrementRecipientsTimesContacted(mContext,
                        (String) sendOrSaveMessage.mValues.get(UIProvider.MessageColumns.TO));
                UIProvider.incrementRecipientsTimesContacted(mContext,
                        (String) sendOrSaveMessage.mValues.get(UIProvider.MessageColumns.CC));
                UIProvider.incrementRecipientsTimesContacted(mContext,
                        (String) sendOrSaveMessage.mValues.get(UIProvider.MessageColumns.BCC));
            }
            mSendOrSaveCallback.sendOrSaveFinished(SendOrSaveTask.this, true);
        }

        /**
         * Send or Save a message.
         */
        private void sendOrSaveMessage(long messageIdToSave, SendOrSaveMessage sendOrSaveMessage,
                ReplyFromAccount selectedAccount, Message message) {
            final ContentResolver resolver = mContext.getContentResolver();
            final boolean updateExistingMessage = messageIdToSave != UIProvider.INVALID_MESSAGE_ID;

            final String accountMethod = sendOrSaveMessage.mSave ?
                    UIProvider.AccountCallMethods.SAVE_MESSAGE :
                    UIProvider.AccountCallMethods.SEND_MESSAGE;

            try {
                if (updateExistingMessage) {
                    sendOrSaveMessage.mValues.put(BaseColumns._ID, messageIdToSave);

                    final Bundle result = callAccountSendSaveMethod(resolver,
                            selectedAccount.account, accountMethod, sendOrSaveMessage);
                    if (result == null) {
                        // TODO(pwestbro): Once Email supports the call api, remove this block
                        // If null was returned, then the provider didn't handle the call method
                        final Uri updateUri = Uri.parse(sendOrSaveMessage.mSave ?
                                message.saveUri : message.sendUri);
                        resolver.update(updateUri, sendOrSaveMessage.mValues, null, null);
                    }
                } else {
                    final Uri messageUri;
                    final Bundle result = callAccountSendSaveMethod(resolver,
                            selectedAccount.account, accountMethod, sendOrSaveMessage);
                    if (result != null) {
                        // If a non-null value was returned, then the provider handled the call
                        // method
                        messageUri = result.getParcelable(UIProvider.MessageColumns.URI);
                    } else {
                        // TODO(pwestbro): Once Email supports the call api, remove this block
                        messageUri = resolver.insert(
                                sendOrSaveMessage.mSave ? selectedAccount.account.saveDraftUri
                                        : selectedAccount.account.sendMessageUri,
                                sendOrSaveMessage.mValues);
                    }
                    if (sendOrSaveMessage.mSave && messageUri != null) {
                        final Cursor messageCursor = resolver.query(messageUri,
                                UIProvider.MESSAGE_PROJECTION, null, null, null);
                        if (messageCursor != null) {
                            try {
                                if (messageCursor.moveToFirst()) {
                                    // Broadcast notification that a new message has
                                    // been allocated
                                    mSendOrSaveCallback.notifyMessageIdAllocated(sendOrSaveMessage,
                                            new Message(messageCursor));
                                }
                            } finally {
                                messageCursor.close();
                            }
                        }
                    }
                }
            } finally {
                // Close any opened file descriptors
                closeOpenedAttachmentFds(sendOrSaveMessage);
            }
        }

        private void closeOpenedAttachmentFds(SendOrSaveMessage sendOrSaveMessage) {
            final Bundle openedFds = sendOrSaveMessage.attachmentFds();
            if (openedFds != null) {
                final Set<String> keys = openedFds.keySet();
                for (String key : keys) {
                    final ParcelFileDescriptor fd = openedFds.getParcelable(key);
                    if (fd != null) {
                        try {
                            fd.close();
                        } catch (IOException e) {
                            // Do nothing
                        }
                    }
                }
            }
        }

        /**
         * Use the {@link ContentResolver#call()} method to send or save the message.
         *
         * If this was successful, this method will return an non-null Bundle instance
         */
        private Bundle callAccountSendSaveMethod(ContentResolver resolver, Account account,
                String method, SendOrSaveMessage sendOrSaveMessage) {
            // Copy all of the values from the content values to the bundle
            final Bundle methodExtras = new Bundle(sendOrSaveMessage.mValues.size());
            final Set<Entry<String, Object>> valueSet = sendOrSaveMessage.mValues.valueSet();

            for (Entry<String, Object> entry : valueSet) {
                final Object entryValue = entry.getValue();
                final String key = entry.getKey();
                if (entryValue instanceof String) {
                    methodExtras.putString(key, (String)entryValue);
                } else if (entryValue instanceof Boolean) {
                    methodExtras.putBoolean(key, (Boolean)entryValue);
                } else if (entryValue instanceof Integer) {
                    methodExtras.putInt(key, (Integer)entryValue);
                } else if (entryValue instanceof Long) {
                    methodExtras.putLong(key, (Long)entryValue);
                } else {
                    LogUtils.wtf(LOG_TAG, "Unexpected object type: %s",
                            entryValue.getClass().getName());
                }
            }

            // If the SendOrSaveMessage has some opened fds, add them to the bundle
            final Bundle fdMap = sendOrSaveMessage.attachmentFds();
            if (fdMap != null) {
                methodExtras.putParcelable(
                        UIProvider.SendOrSaveMethodParamKeys.OPENED_FD_MAP, fdMap);
            }

            return resolver.call(account.uri, method, account.uri.toString(), methodExtras);
        }
    }

    @VisibleForTesting
    public static class SendOrSaveMessage {
        final ReplyFromAccount mAccount;
        final ContentValues mValues;
        final String mRefMessageId;
        @VisibleForTesting
        public final boolean mSave;
        final int mRequestId;
        private final Bundle mAttachmentFds;

        public SendOrSaveMessage(Context context, ReplyFromAccount account, ContentValues values,
                String refMessageId, List<Attachment> attachments, boolean save) {
            mAccount = account;
            mValues = values;
            mRefMessageId = refMessageId;
            mSave = save;
            mRequestId = mValues.hashCode() ^ hashCode();

            mAttachmentFds = initializeAttachmentFds(context, attachments);
        }

        int requestId() {
            return mRequestId;
        }

        Bundle attachmentFds() {
            return mAttachmentFds;
        }

        /**
         * Opens {@link ParcelFileDescriptor} for each of the attachments.  This method must be
         * called before the ComposeActivity finishes.
         * Note: The caller is responsible for closing these file descriptors.
         */
        private Bundle initializeAttachmentFds(Context context, List<Attachment> attachments) {
            if (attachments == null || attachments.size() == 0) {
                return null;
            }

            final Bundle result = new Bundle(attachments.size());
            final ContentResolver resolver = context.getContentResolver();

            for (Attachment attachment : attachments) {
                if (attachment == null || Utils.isEmpty(attachment.contentUri)) {
                    continue;
                }

                ParcelFileDescriptor fileDescriptor;
                try {
                    fileDescriptor = resolver.openFileDescriptor(attachment.contentUri, "r");
                } catch (FileNotFoundException e) {
                    LogUtils.e(LOG_TAG, e, "Exception attempting to open attachment");
                    fileDescriptor = null;
                }

                if (fileDescriptor != null) {
                    result.putParcelable(attachment.contentUri.toString(), fileDescriptor);
                }
            }

            return result;
        }
    }

    /**
     * Get the to recipients.
     */
    public String[] getToAddresses() {
        return getAddressesFromList(mTo);
    }

    /**
     * Get the cc recipients.
     */
    public String[] getCcAddresses() {
        return getAddressesFromList(mCc);
    }

    /**
     * Get the bcc recipients.
     */
    public String[] getBccAddresses() {
        return getAddressesFromList(mBcc);
    }

    public String[] getAddressesFromList(RecipientEditTextView list) {
        if (list == null) {
            return new String[0];
        }
        Rfc822Token[] tokens = Rfc822Tokenizer.tokenize(list.getText());
        int count = tokens.length;
        String[] result = new String[count];
        for (int i = 0; i < count; i++) {
            result[i] = tokens[i].toString();
        }
        return result;
    }

    /**
     * Check for invalid email addresses.
     * @param to String array of email addresses to check.
     * @param wrongEmailsOut Emails addresses that were invalid.
     */
    public void checkInvalidEmails(String[] to, List<String> wrongEmailsOut) {
        if (mValidator == null) {
            return;
        }
        for (String email : to) {
            if (!mValidator.isValid(email)) {
                wrongEmailsOut.add(email);
            }
        }
    }

    /**
     * Show an error because the user has entered an invalid recipient.
     * @param message
     */
    public void showRecipientErrorDialog(String message) {
        // Only 1 invalid recipients error dialog should be allowed up at a
        // time.
        if (mRecipientErrorDialog != null) {
            mRecipientErrorDialog.dismiss();
        }
        mRecipientErrorDialog = new AlertDialog.Builder(this).setMessage(message).setTitle(
                R.string.recipient_error_dialog_title)
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setPositiveButton(
                        R.string.ok, new Dialog.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                // after the user dismisses the recipient error
                                // dialog we want to make sure to refocus the
                                // recipient to field so they can fix the issue
                                // easily
                                if (mTo != null) {
                                    mTo.requestFocus();
                                }
                                mRecipientErrorDialog = null;
                            }
                        }).show();
    }

    /**
     * Update the state of the UI based on whether or not the current draft
     * needs to be saved and the message is not empty.
     */
    public void updateSaveUi() {
        if (mSave != null) {
            mSave.setEnabled((shouldSave() && !isBlank()));
        }
    }

    /**
     * Returns true if we need to save the current draft.
     */
    private boolean shouldSave() {
        synchronized (mDraftLock) {
            // The message should only be saved if:
            // It hasn't been sent AND
            // Some text has been added to the message OR
            // an attachment has been added or removed
            // AND there is actually something in the draft to save.
            return (mTextChanged || mAttachmentsChanged || mReplyFromChanged)
                    && !isBlank();
        }
    }

    /**
     * Check if all fields are blank.
     * @return boolean
     */
    public boolean isBlank() {
        return mSubject.getText().length() == 0
                && (mBodyView.getText().length() == 0 || getSignatureStartPosition(mSignature,
                        mBodyView.getText().toString()) == 0)
                && mTo.length() == 0
                && mCc.length() == 0 && mBcc.length() == 0
                && mAttachmentsView.getAttachments().size() == 0;
    }

    @VisibleForTesting
    protected int getSignatureStartPosition(String signature, String bodyText) {
        int startPos = -1;

        if (TextUtils.isEmpty(signature) || TextUtils.isEmpty(bodyText)) {
            return startPos;
        }

        int bodyLength = bodyText.length();
        int signatureLength = signature.length();
        String printableVersion = convertToPrintableSignature(signature);
        int printableLength = printableVersion.length();

        if (bodyLength >= printableLength
                && bodyText.substring(bodyLength - printableLength)
                .equals(printableVersion)) {
            startPos = bodyLength - printableLength;
        } else if (bodyLength >= signatureLength
                && bodyText.substring(bodyLength - signatureLength)
                .equals(signature)) {
            startPos = bodyLength - signatureLength;
        }
        return startPos;
    }

    /**
     * Allows any changes made by the user to be ignored. Called when the user
     * decides to discard a draft.
     */
    private void discardChanges() {
        mTextChanged = false;
        mAttachmentsChanged = false;
        mReplyFromChanged = false;
    }

    /**
     * @param body
     * @param save
     * @param showToast
     * @return Whether the send or save succeeded.
     */
    protected boolean sendOrSaveWithSanityChecks(final boolean save, final boolean showToast,
            final boolean orientationChanged, final boolean autoSend) {
        if (mAccounts == null || mAccount == null) {
            Toast.makeText(this, R.string.send_failed, Toast.LENGTH_SHORT).show();
            if (autoSend) {
                finish();
            }
            return false;
        }

        String[] to, cc, bcc;
        Editable body = mBodyView.getEditableText();
        if (orientationChanged) {
            to = cc = bcc = new String[0];
        } else {
            to = getToAddresses();
            cc = getCcAddresses();
            bcc = getBccAddresses();
        }

        // Don't let the user send to nobody (but it's okay to save a message
        // with no recipients)
        if (!save && (to.length == 0 && cc.length == 0 && bcc.length == 0)) {
            showRecipientErrorDialog(getString(R.string.recipient_needed));
            return false;
        }

        List<String> wrongEmails = new ArrayList<String>();
        if (!save) {
            checkInvalidEmails(to, wrongEmails);
            checkInvalidEmails(cc, wrongEmails);
            checkInvalidEmails(bcc, wrongEmails);
        }

        // Don't let the user send an email with invalid recipients
        if (wrongEmails.size() > 0) {
            String errorText = String.format(getString(R.string.invalid_recipient),
                    wrongEmails.get(0));
            showRecipientErrorDialog(errorText);
            return false;
        }

        DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                sendOrSave(mBodyView.getEditableText(), save, showToast, orientationChanged);
            }
        };

        // Show a warning before sending only if there are no attachments.
        if (!save) {
            if (mAttachmentsView.getAttachments().isEmpty() && showEmptyTextWarnings()) {
                boolean warnAboutEmptySubject = isSubjectEmpty();
                boolean emptyBody = TextUtils.getTrimmedLength(body) == 0;

                // A warning about an empty body may not be warranted when
                // forwarding mails, since a common use case is to forward
                // quoted text and not append any more text.
                boolean warnAboutEmptyBody = emptyBody && (!mForward || isBodyEmpty());

                // When we bring up a dialog warning the user about a send,
                // assume that they accept sending the message. If they do not,
                // the dialog listener is required to enable sending again.
                if (warnAboutEmptySubject) {
                    showSendConfirmDialog(R.string.confirm_send_message_with_no_subject, listener);
                    return true;
                }

                if (warnAboutEmptyBody) {
                    showSendConfirmDialog(R.string.confirm_send_message_with_no_body, listener);
                    return true;
                }
            }
            // Ask for confirmation to send (if always required)
            if (showSendConfirmation()) {
                showSendConfirmDialog(R.string.confirm_send_message, listener);
                return true;
            }
        }

        sendOrSave(body, save, showToast, false);
        return true;
    }

    /**
     * Returns a boolean indicating whether warnings should be shown for empty
     * subject and body fields
     *
     * @return True if a warning should be shown for empty text fields
     */
    protected boolean showEmptyTextWarnings() {
        return mAttachmentsView.getAttachments().size() == 0;
    }

    /**
     * Returns a boolean indicating whether the user should confirm each send
     *
     * @return True if a warning should be on each send
     */
    protected boolean showSendConfirmation() {
        return mCachedSettings != null ? mCachedSettings.confirmSend : false;
    }

    private void showSendConfirmDialog(int messageId, DialogInterface.OnClickListener listener) {
        if (mSendConfirmDialog != null) {
            mSendConfirmDialog.dismiss();
            mSendConfirmDialog = null;
        }
        mSendConfirmDialog = new AlertDialog.Builder(this).setMessage(messageId)
                .setTitle(R.string.confirm_send_title)
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setPositiveButton(R.string.send, listener)
                .setNegativeButton(R.string.cancel, this)
                .show();
    }

    /**
     * Returns whether the ComposeArea believes there is any text in the body of
     * the composition. TODO: When ComposeArea controls the Body as well, add
     * that here.
     */
    public boolean isBodyEmpty() {
        return !mQuotedTextView.isTextIncluded();
    }

    /**
     * Test to see if the subject is empty.
     *
     * @return boolean.
     */
    // TODO: this will likely go away when composeArea.focus() is implemented
    // after all the widget control is moved over.
    public boolean isSubjectEmpty() {
        return TextUtils.getTrimmedLength(mSubject.getText()) == 0;
    }

    /* package */
    static int sendOrSaveInternal(Context context, ReplyFromAccount replyFromAccount,
            Message message, final Message refMessage, Spanned body, final CharSequence quotedText,
            SendOrSaveCallback callback, Handler handler, boolean save, int composeMode,
            ReplyFromAccount draftAccount) {
        ContentValues values = new ContentValues();

        String refMessageId = refMessage != null ? refMessage.uri.toString() : "";

        MessageModification.putToAddresses(values, message.getToAddresses());
        MessageModification.putCcAddresses(values, message.getCcAddresses());
        MessageModification.putBccAddresses(values, message.getBccAddresses());

        MessageModification.putCustomFromAddress(values, message.getFrom());

        MessageModification.putSubject(values, message.subject);
        String htmlBody = Html.toHtml(new SpannableString(body.toString()));

        boolean includeQuotedText = !TextUtils.isEmpty(quotedText);
        StringBuilder fullBody = new StringBuilder(htmlBody);
        if (includeQuotedText) {
            // HTML gets converted to text for now
            final String text = quotedText.toString();
            if (QuotedTextView.containsQuotedText(text)) {
                int pos = QuotedTextView.getQuotedTextOffset(text);
                final int quoteStartPos = fullBody.length() + pos;
                fullBody.append(text);
                MessageModification.putQuoteStartPos(values, quoteStartPos);
                MessageModification.putForward(values, composeMode == ComposeActivity.FORWARD);
                MessageModification.putAppendRefMessageContent(values, includeQuotedText);
            } else {
                LogUtils.w(LOG_TAG, "Couldn't find quoted text");
                // This shouldn't happen, but just use what we have,
                // and don't do server-side expansion
                fullBody.append(text);
            }
        }
        int draftType = getDraftType(composeMode);
        MessageModification.putDraftType(values, draftType);
        if (refMessage != null) {
            if (!TextUtils.isEmpty(refMessage.bodyHtml)) {
                MessageModification.putBodyHtml(values, fullBody.toString());
            }
            if (!TextUtils.isEmpty(refMessage.bodyText)) {
                MessageModification.putBody(values,
                        Utils.convertHtmlToPlainText(fullBody.toString()).toString());
            }
        } else {
            MessageModification.putBodyHtml(values, fullBody.toString());
            MessageModification.putBody(values, Utils.convertHtmlToPlainText(fullBody.toString())
                    .toString());
        }
        MessageModification.putAttachments(values, message.getAttachments());
        if (!TextUtils.isEmpty(refMessageId)) {
            MessageModification.putRefMessageId(values, refMessageId);
        }
        SendOrSaveMessage sendOrSaveMessage = new SendOrSaveMessage(context, replyFromAccount,
                values, refMessageId, message.getAttachments(), save);
        SendOrSaveTask sendOrSaveTask = new SendOrSaveTask(context, sendOrSaveMessage, callback,
                draftAccount);

        callback.initializeSendOrSave(sendOrSaveTask);
        // Do the send/save action on the specified handler to avoid possible
        // ANRs
        handler.post(sendOrSaveTask);

        return sendOrSaveMessage.requestId();
    }

    private static int getDraftType(int mode) {
        int draftType = -1;
        switch (mode) {
            case ComposeActivity.COMPOSE:
                draftType = DraftType.COMPOSE;
                break;
            case ComposeActivity.REPLY:
                draftType = DraftType.REPLY;
                break;
            case ComposeActivity.REPLY_ALL:
                draftType = DraftType.REPLY_ALL;
                break;
            case ComposeActivity.FORWARD:
                draftType = DraftType.FORWARD;
                break;
        }
        return draftType;
    }

    private void sendOrSave(Spanned body, boolean save, boolean showToast,
            boolean orientationChanged) {
        // Check if user is a monkey. Monkeys can compose and hit send
        // button but are not allowed to send anything off the device.
        if (ActivityManager.isUserAMonkey()) {
            return;
        }

        String[] to, cc, bcc;
        if (orientationChanged) {
            to = cc = bcc = new String[0];
        } else {
            to = getToAddresses();
            cc = getCcAddresses();
            bcc = getBccAddresses();
        }

        SendOrSaveCallback callback = new SendOrSaveCallback() {
            // FIXME: unused
            private int mRestoredRequestId;

            @Override
            public void initializeSendOrSave(SendOrSaveTask sendOrSaveTask) {
                synchronized (mActiveTasks) {
                    int numTasks = mActiveTasks.size();
                    if (numTasks == 0) {
                        // Start service so we won't be killed if this app is
                        // put in the background.
                        startService(new Intent(ComposeActivity.this, EmptyService.class));
                    }

                    mActiveTasks.add(sendOrSaveTask);
                }
                if (sTestSendOrSaveCallback != null) {
                    sTestSendOrSaveCallback.initializeSendOrSave(sendOrSaveTask);
                }
            }

            @Override
            public void notifyMessageIdAllocated(SendOrSaveMessage sendOrSaveMessage,
                    Message message) {
                synchronized (mDraftLock) {
                    mDraftAccount = sendOrSaveMessage.mAccount;
                    mDraftId = message.id;
                    mDraft = message;
                    if (sRequestMessageIdMap != null) {
                        sRequestMessageIdMap.put(sendOrSaveMessage.requestId(), mDraftId);
                    }
                    // Cache request message map, in case the process is killed
                    saveRequestMap();
                }
                if (sTestSendOrSaveCallback != null) {
                    sTestSendOrSaveCallback.notifyMessageIdAllocated(sendOrSaveMessage, message);
                }
            }

            @Override
            public Message getMessage() {
                synchronized (mDraftLock) {
                    return mDraft;
                }
            }

            @Override
            public void sendOrSaveFinished(SendOrSaveTask task, boolean success) {
                // Update the last sent from account.
                if (mAccount != null) {
                    MailAppProvider.getInstance().setLastSentFromAccount(mAccount.uri.toString());
                }
                if (success) {
                    // Successfully sent or saved so reset change markers
                    discardChanges();
                } else {
                    // A failure happened with saving/sending the draft
                    // TODO(pwestbro): add a better string that should be used
                    // when failing to send or save
                    Toast.makeText(ComposeActivity.this, R.string.send_failed, Toast.LENGTH_SHORT)
                            .show();
                }

                int numTasks;
                synchronized (mActiveTasks) {
                    // Remove the task from the list of active tasks
                    mActiveTasks.remove(task);
                    numTasks = mActiveTasks.size();
                }

                if (numTasks == 0) {
                    // Stop service so we can be killed.
                    stopService(new Intent(ComposeActivity.this, EmptyService.class));
                }
                if (sTestSendOrSaveCallback != null) {
                    sTestSendOrSaveCallback.sendOrSaveFinished(task, success);
                }
            }
        };

        // Get the selected account if the from spinner has been setup.
        ReplyFromAccount selectedAccount = mReplyFromAccount;
        String fromAddress = selectedAccount.name;
        if (selectedAccount == null || fromAddress == null) {
            // We don't have either the selected account or from address,
            // use mAccount.
            selectedAccount = mReplyFromAccount;
            fromAddress = mAccount.name;
        }

        if (mSendSaveTaskHandler == null) {
            HandlerThread handlerThread = new HandlerThread("Send Message Task Thread");
            handlerThread.start();

            mSendSaveTaskHandler = new Handler(handlerThread.getLooper());
        }

        Message msg = createMessage(mReplyFromAccount, getMode());
        mRequestId = sendOrSaveInternal(this, mReplyFromAccount, msg, mRefMessage, body,
                mQuotedTextView.getQuotedTextIfIncluded(), callback,
                mSendSaveTaskHandler, save, mComposeMode, mDraftAccount);

        if (mRecipient != null && mRecipient.equals(mAccount.name)) {
            mRecipient = selectedAccount.name;
        }
        setAccount(selectedAccount.account);

        // Don't display the toast if the user is just changing the orientation,
        // but we still need to save the draft to the cursor because this is how we restore
        // the attachments when the configuration change completes.
        if (showToast && (getChangingConfigurations() & ActivityInfo.CONFIG_ORIENTATION) == 0) {
            Toast.makeText(this, save ? R.string.message_saved : R.string.sending_message,
                    Toast.LENGTH_LONG).show();
        }

        // Need to update variables here because the send or save completes
        // asynchronously even though the toast shows right away.
        discardChanges();
        updateSaveUi();

        // If we are sending, finish the activity
        if (!save) {
            finish();
        }
    }

    /**
     * Save the state of the request messageid map. This allows for the Gmail
     * process to be killed, but and still allow for ComposeActivity instances
     * to be recreated correctly.
     */
    private void saveRequestMap() {
        // TODO: store the request map in user preferences.
    }

    private void doAttach(String type) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
        i.setType(type);
        mAddingAttachment = true;
        startActivityForResult(Intent.createChooser(i, getText(R.string.select_attachment_type)),
                RESULT_PICK_ATTACHMENT);
    }

    private void showCcBccViews() {
        mCcBccView.show(true, true, true);
        if (mCcBccButton != null) {
            mCcBccButton.setVisibility(View.INVISIBLE);
        }
    }

    @Override
    public boolean onNavigationItemSelected(int position, long itemId) {
        int initialComposeMode = mComposeMode;
        if (position == ComposeActivity.REPLY) {
            mComposeMode = ComposeActivity.REPLY;
        } else if (position == ComposeActivity.REPLY_ALL) {
            mComposeMode = ComposeActivity.REPLY_ALL;
        } else if (position == ComposeActivity.FORWARD) {
            mComposeMode = ComposeActivity.FORWARD;
        }
        clearChangeListeners();
        if (initialComposeMode != mComposeMode) {
            resetMessageForModeChange();
            if (mRefMessage != null) {
                setFieldsFromRefMessage(mComposeMode);
            }
            boolean showCc = false;
            boolean showBcc = false;
            if (mDraft != null) {
                // Following desktop behavior, if the user has added a BCC
                // field to a draft, we show it regardless of compose mode.
                showBcc = !TextUtils.isEmpty(mDraft.getBcc());
                // Use the draft to determine what to populate.
                // If the Bcc field is showing, show the Cc field whether it is populated or not.
                showCc = showBcc
                        || (!TextUtils.isEmpty(mDraft.getCc()) && mComposeMode == REPLY_ALL);
            }
            if (mRefMessage != null) {
                showCc = !TextUtils.isEmpty(mCc.getText());
                showBcc = !TextUtils.isEmpty(mBcc.getText());
            }
            mCcBccView.show(false, showCc, showBcc);
        }
        updateHideOrShowCcBcc();
        initChangeListeners();
        return true;
    }

    @VisibleForTesting
    protected void resetMessageForModeChange() {
        // When switching between reply, reply all, forward,
        // follow the behavior of webview.
        // The contents of the following fields are cleared
        // so that they can be populated directly from the
        // ref message:
        // 1) Any recipient fields
        // 2) The subject
        mTo.setText("");
        mCc.setText("");
        mBcc.setText("");
        // Any edits to the subject are replaced with the original subject.
        mSubject.setText("");

        // Any changes to the contents of the following fields are kept:
        // 1) Body
        // 2) Attachments
        // If the user made changes to attachments, keep their changes.
        if (!mAttachmentsChanged) {
            mAttachmentsView.deleteAllAttachments();
        }
    }

    private class ComposeModeAdapter extends ArrayAdapter<String> {

        private LayoutInflater mInflater;

        public ComposeModeAdapter(Context context) {
            super(context, R.layout.compose_mode_item, R.id.mode, getResources()
                    .getStringArray(R.array.compose_modes));
        }

        private LayoutInflater getInflater() {
            if (mInflater == null) {
                mInflater = LayoutInflater.from(getContext());
            }
            return mInflater;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = getInflater().inflate(R.layout.compose_mode_display_item, null);
            }
            ((TextView) convertView.findViewById(R.id.mode)).setText(getItem(position));
            return super.getView(position, convertView, parent);
        }
    }

    @Override
    public void onRespondInline(String text) {
        appendToBody(text, false);
        mQuotedTextView.setUpperDividerVisible(false);
        mRespondedInline = true;
        if (!mBodyView.hasFocus()) {
            mBodyView.requestFocus();
        }
    }

    /**
     * Append text to the body of the message. If there is no existing body
     * text, just sets the body to text.
     *
     * @param text
     * @param withSignature True to append a signature.
     */
    public void appendToBody(CharSequence text, boolean withSignature) {
        Editable bodyText = mBodyView.getEditableText();
        if (bodyText != null && bodyText.length() > 0) {
            bodyText.append(text);
        } else {
            setBody(text, withSignature);
        }
    }

    /**
     * Set the body of the message.
     *
     * @param text
     * @param withSignature True to append a signature.
     */
    public void setBody(CharSequence text, boolean withSignature) {
        mBodyView.setText(text);
        if (withSignature) {
            appendSignature();
        }
    }

    private void appendSignature() {
        String newSignature = mCachedSettings != null ? mCachedSettings.signature : null;
        boolean hasFocus = mBodyView.hasFocus();
        int signaturePos = getSignatureStartPosition(mSignature, mBodyView.getText().toString());
        if (!TextUtils.equals(newSignature, mSignature) || signaturePos < 0) {
            mSignature = newSignature;
            if (!TextUtils.isEmpty(mSignature)) {
                // Appending a signature does not count as changing text.
                mBodyView.removeTextChangedListener(this);
                mBodyView.append(convertToPrintableSignature(mSignature));
                mBodyView.addTextChangedListener(this);
            }
            if (hasFocus) {
                focusBody();
            }
        }
    }

    private String convertToPrintableSignature(String signature) {
        String signatureResource = getResources().getString(R.string.signature);
        if (signature == null) {
            signature = "";
        }
        return String.format(signatureResource, signature);
    }

    @Override
    public void onAccountChanged() {
        mReplyFromAccount = mFromSpinner.getCurrentAccount();
        if (!mAccount.equals(mReplyFromAccount.account)) {
            // Clear a signature, if there was one.
            mBodyView.removeTextChangedListener(this);
            String oldSignature = mSignature;
            String bodyText = getBody().getText().toString();
            if (!TextUtils.isEmpty(oldSignature)) {
                int pos = getSignatureStartPosition(oldSignature, bodyText);
                if (pos > -1) {
                    mBodyView.setText(bodyText.substring(0, pos));
                }
            }
            setAccount(mReplyFromAccount.account);
            mBodyView.addTextChangedListener(this);
            // TODO: handle discarding attachments when switching accounts.
            // Only enable save for this draft if there is any other content
            // in the message.
            if (!isBlank()) {
                enableSave(true);
            }
            mReplyFromChanged = true;
            initRecipients();
        }
    }

    public void enableSave(boolean enabled) {
        if (mSave != null) {
            mSave.setEnabled(enabled);
        }
    }

    public void enableSend(boolean enabled) {
        if (mSend != null) {
            mSend.setEnabled(enabled);
        }
    }

    /**
     * Handles button clicks from any error dialogs dealing with sending
     * a message.
     */
    @Override
    public void onClick(DialogInterface dialog, int which) {
        switch (which) {
            case DialogInterface.BUTTON_POSITIVE: {
                doDiscardWithoutConfirmation(true /* show toast */ );
                break;
            }
            case DialogInterface.BUTTON_NEGATIVE: {
                // If the user cancels the send, re-enable the send button.
                enableSend(true);
                break;
            }
        }

    }

    private void doDiscard() {
        new AlertDialog.Builder(this).setMessage(R.string.confirm_discard_text)
                .setPositiveButton(R.string.ok, this)
                .setNegativeButton(R.string.cancel, null)
                .create().show();
    }
    /**
     * Effectively discard the current message.
     *
     * This method is either invoked from the menu or from the dialog
     * once the user has confirmed that they want to discard the message.
     * @param showToast show "Message discarded" toast if true
     */
    private void doDiscardWithoutConfirmation(boolean showToast) {
        synchronized (mDraftLock) {
            if (mDraftId != UIProvider.INVALID_MESSAGE_ID) {
                ContentValues values = new ContentValues();
                values.put(BaseColumns._ID, mDraftId);
                if (!mAccount.expungeMessageUri.equals(Uri.EMPTY)) {
                    getContentResolver().update(mAccount.expungeMessageUri, values, null, null);
                } else {
                    getContentResolver().delete(mDraft.uri, null, null);
                }
                // This is not strictly necessary (since we should not try to
                // save the draft after calling this) but it ensures that if we
                // do save again for some reason we make a new draft rather than
                // trying to resave an expunged draft.
                mDraftId = UIProvider.INVALID_MESSAGE_ID;
            }
        }

        if (showToast) {
            // Display a toast to let the user know
            Toast.makeText(this, R.string.message_discarded, Toast.LENGTH_SHORT).show();
        }

        // This prevents the draft from being saved in onPause().
        discardChanges();
        finish();
    }

    private void saveIfNeeded() {
        if (mAccount == null) {
            // We have not chosen an account yet so there's no way that we can save. This is ok,
            // though, since we are saving our state before AccountsActivity is activated. Thus, the
            // user has not interacted with us yet and there is no real state to save.
            return;
        }

        if (shouldSave()) {
            doSave(!mAddingAttachment /* show toast */);
        }
    }

    @Override
    public void onAttachmentDeleted() {
        mAttachmentsChanged = true;
        // If we are showing any attachments, make sure we have an upper
        // divider.
        mQuotedTextView.setUpperDividerVisible(mAttachmentsView.getAttachments().size() > 0);
        updateSaveUi();
    }

    @Override
    public void onAttachmentAdded() {
        mQuotedTextView.setUpperDividerVisible(mAttachmentsView.getAttachments().size() > 0);
        mAttachmentsView.focusLastAttachment();
    }

    /**
     * This is called any time one of our text fields changes.
     */
    @Override
    public void afterTextChanged(Editable s) {
        mTextChanged = true;
        updateSaveUi();
    }

    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        // Do nothing.
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
        // Do nothing.
    }


    // There is a big difference between the text associated with an address changing
    // to add the display name or to format properly and a recipient being added or deleted.
    // Make sure we only notify of changes when a recipient has been added or deleted.
    private class RecipientTextWatcher implements TextWatcher {
        private HashMap<String, Integer> mContent = new HashMap<String, Integer>();

        private RecipientEditTextView mView;

        private TextWatcher mListener;

        public RecipientTextWatcher(RecipientEditTextView view, TextWatcher listener) {
            mView = view;
            mListener = listener;
        }

        @Override
        public void afterTextChanged(Editable s) {
            if (hasChanged()) {
                mListener.afterTextChanged(s);
            }
        }

        private boolean hasChanged() {
            String[] currRecips = tokenizeRecips(getAddressesFromList(mView));
            int totalCount = currRecips.length;
            int totalPrevCount = 0;
            for (Entry<String, Integer> entry : mContent.entrySet()) {
                totalPrevCount += entry.getValue();
            }
            if (totalCount != totalPrevCount) {
                return true;
            }

            for (String recip : currRecips) {
                if (!mContent.containsKey(recip)) {
                    return true;
                } else {
                    int count = mContent.get(recip) - 1;
                    if (count < 0) {
                        return true;
                    } else {
                        mContent.put(recip, count);
                    }
                }
            }
            return false;
        }

        private String[] tokenizeRecips(String[] recips) {
            // Tokenize them all and put them in the list.
            String[] recipAddresses = new String[recips.length];
            for (int i = 0; i < recips.length; i++) {
                recipAddresses[i] = Rfc822Tokenizer.tokenize(recips[i])[0].getAddress();
            }
            return recipAddresses;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            String[] recips = tokenizeRecips(getAddressesFromList(mView));
            for (String recip : recips) {
                if (!mContent.containsKey(recip)) {
                    mContent.put(recip, 1);
                } else {
                    mContent.put(recip, (mContent.get(recip)) + 1);
                }
            }
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            // Do nothing.
        }
    }

    public static void registerTestSendOrSaveCallback(SendOrSaveCallback testCallback) {
        if (sTestSendOrSaveCallback != null && testCallback != null) {
            throw new IllegalStateException("Attempting to register more than one test callback");
        }
        sTestSendOrSaveCallback = testCallback;
    }

    @VisibleForTesting
    protected ArrayList<Attachment> getAttachments() {
        return mAttachmentsView.getAttachments();
    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        switch (id) {
            case REFERENCE_MESSAGE_LOADER:
                return new CursorLoader(this, mRefMessageUri, UIProvider.MESSAGE_PROJECTION, null,
                        null, null);
            case LOADER_ACCOUNT_CURSOR:
                return new CursorLoader(this, MailAppProvider.getAccountsUri(),
                        UIProvider.ACCOUNTS_PROJECTION, null, null, null);
        }
        return null;
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        int id = loader.getId();
        switch (id) {
            case REFERENCE_MESSAGE_LOADER:
                if (data != null && data.moveToFirst()) {
                    mRefMessage = new Message(data);
                    Intent intent = getIntent();
                    int action = intent.getIntExtra(EXTRA_ACTION, COMPOSE);
                    initFromRefMessage(action);
                    finishSetup(action, intent, null, true);
                    if (action != FORWARD) {
                        String to = intent.getStringExtra(EXTRA_TO);
                        if (!TextUtils.isEmpty(to)) {
                            mRefMessage.setTo(null);
                            mRefMessage.setFrom(null);
                            clearChangeListeners();
                            mTo.append(to);
                            initChangeListeners();
                        }
                    }
                } else {
                    finish();
                }
                break;
            case LOADER_ACCOUNT_CURSOR:
                if (data != null && data.moveToFirst()) {
                    // there are accounts now!
                    Account account;
                    final ArrayList<Account> accounts = new ArrayList<Account>();
                    final ArrayList<Account> initializedAccounts = new ArrayList<Account>();
                    do {
                        account = new Account(data);
                        if (account.isAccountReady()) {
                            initializedAccounts.add(account);
                        }
                        accounts.add(account);
                    } while (data.moveToNext());
                    if (initializedAccounts.size() > 0) {
                        findViewById(R.id.wait).setVisibility(View.GONE);
                        getLoaderManager().destroyLoader(LOADER_ACCOUNT_CURSOR);
                        findViewById(R.id.compose).setVisibility(View.VISIBLE);
                        mAccounts = initializedAccounts.toArray(
                                new Account[initializedAccounts.size()]);

                        finishCreate();
                        invalidateOptionsMenu();
                    } else {
                        // Show "waiting"
                        account = accounts.size() > 0 ? accounts.get(0) : null;
                        showWaitFragment(account);
                    }
                }
                break;
        }
    }

    private void showWaitFragment(Account account) {
        WaitFragment fragment = getWaitFragment();
        if (fragment != null) {
            fragment.updateAccount(account);
        } else {
            findViewById(R.id.wait).setVisibility(View.VISIBLE);
            replaceFragment(WaitFragment.newInstance(account, true),
                    FragmentTransaction.TRANSIT_FRAGMENT_OPEN, TAG_WAIT);
        }
    }

    private WaitFragment getWaitFragment() {
        return (WaitFragment) getFragmentManager().findFragmentByTag(TAG_WAIT);
    }

    private int replaceFragment(Fragment fragment, int transition, String tag) {
        FragmentTransaction fragmentTransaction = getFragmentManager().beginTransaction();
        fragmentTransaction.addToBackStack(null);
        fragmentTransaction.setTransition(transition);
        fragmentTransaction.replace(R.id.wait, fragment, tag);
        final int transactionId = fragmentTransaction.commitAllowingStateLoss();
        return transactionId;
    }

    @Override
    public void onLoaderReset(Loader<Cursor> arg0) {
        // Do nothing.
    }
}
