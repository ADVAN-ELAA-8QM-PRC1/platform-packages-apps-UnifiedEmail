/*
 * Copyright (C) 2012 Google Inc.
 * Licensed to The Android Open Source Project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.mail.ui;

import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.app.Fragment;
import android.app.LoaderManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.Loader;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.DataSetObservable;
import android.database.DataSetObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Browser;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.android.mail.ContactInfo;
import com.android.mail.ContactInfoSource;
import com.android.mail.FormattedDateBuilder;
import com.android.mail.R;
import com.android.mail.SenderInfoLoader;
import com.android.mail.browse.ConversationAccountController;
import com.android.mail.browse.ConversationViewHeader.ConversationViewHeaderCallbacks;
import com.android.mail.browse.MessageCursor;
import com.android.mail.browse.MessageCursor.ConversationController;
import com.android.mail.browse.MessageHeaderView.MessageHeaderViewCallbacks;
import com.android.mail.providers.Account;
import com.android.mail.providers.AccountObserver;
import com.android.mail.providers.Address;
import com.android.mail.providers.Conversation;
import com.android.mail.providers.Folder;
import com.android.mail.providers.ListParams;
import com.android.mail.providers.UIProvider;
import com.android.mail.providers.UIProvider.CursorStatus;
import com.android.mail.utils.LogTag;
import com.android.mail.utils.LogUtils;
import com.android.mail.utils.Utils;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

public abstract class AbstractConversationViewFragment extends Fragment implements
        ConversationController, ConversationAccountController, MessageHeaderViewCallbacks,
        ConversationViewHeaderCallbacks {

    private static final String ARG_ACCOUNT = "account";
    public static final String ARG_CONVERSATION = "conversation";
    private static final String ARG_FOLDER = "folder";
    private static final String LOG_TAG = LogTag.getLogTag();
    protected static final int MESSAGE_LOADER = 0;
    protected static final int CONTACT_LOADER = 1;
    private static int sMinDelay = -1;
    private static int sMinShowTime = -1;
    protected ControllableActivity mActivity;
    private final MessageLoaderCallbacks mMessageLoaderCallbacks = new MessageLoaderCallbacks();
    protected FormattedDateBuilder mDateBuilder;
    private final ContactLoaderCallbacks mContactLoaderCallbacks = new ContactLoaderCallbacks();
    private MenuItem mChangeFoldersMenuItem;
    protected Conversation mConversation;
    protected Folder mFolder;
    protected String mBaseUri;
    protected Account mAccount;
    protected final Map<String, Address> mAddressCache = Maps.newHashMap();
    protected boolean mEnableContentReadySignal;
    private MessageCursor mCursor;
    private Context mContext;
    /**
     * A backwards-compatible version of {{@link #getUserVisibleHint()}. Like the framework flag,
     * this flag is saved and restored.
     */
    private boolean mUserVisible;
    private View mProgressView;
    private View mBackgroundView;
    private final Handler mHandler = new Handler();
    /** True if we want to avoid marking the conversation as viewed and read. */
    private boolean mSuppressMarkingViewed;
    /**
     * Parcelable state of the conversation view. Can safely be used without null checking any time
     * after {@link #onCreate(Bundle)}.
     */
    protected ConversationViewState mViewState;

    private long mLoadingShownTime = -1;

    private final Runnable mDelayedShow = new FragmentRunnable("mDelayedShow") {
        @Override
        public void go() {
            mLoadingShownTime = System.currentTimeMillis();
            mProgressView.setVisibility(View.VISIBLE);
        }
    };

    private final AccountObserver mAccountObserver = new AccountObserver() {
        @Override
        public void onChanged(Account newAccount) {
            final Account oldAccount = mAccount;
            mAccount = newAccount;
            onAccountChanged(newAccount, oldAccount);
        }
    };

    private static final String BUNDLE_VIEW_STATE =
            AbstractConversationViewFragment.class.getName() + "viewstate";
    /**
     * We save the user visible flag so the various transitions that occur during rotation do not
     * cause unnecessary visibility change.
     */
    private static final String BUNDLE_USER_VISIBLE =
            AbstractConversationViewFragment.class.getName() + "uservisible";

    public static Bundle makeBasicArgs(Account account, Folder folder) {
        Bundle args = new Bundle();
        args.putParcelable(ARG_ACCOUNT, account);
        args.putParcelable(ARG_FOLDER, folder);
        return args;
    }

    /**
     * Constructor needs to be public to handle orientation changes and activity
     * lifecycle events.
     */
    public AbstractConversationViewFragment() {
        super();
    }

    /**
     * Subclasses must override, since this depends on how many messages are
     * shown in the conversation view.
     */
    protected void markUnread() {
        // Do not automatically mark this conversation viewed and read.
        mSuppressMarkingViewed = true;
    }

    /**
     * Subclasses must override this, since they may want to display a single or
     * many messages related to this conversation.
     */
    protected abstract void onMessageCursorLoadFinished(Loader<Cursor> loader,
            MessageCursor newCursor, MessageCursor oldCursor);

    /**
     * Subclasses must override this, since they may want to display a single or
     * many messages related to this conversation.
     */
    @Override
    public abstract void onConversationViewHeaderHeightChange(int newHeight);

    public abstract void onUserVisibleHintChanged();

    /**
     * Subclasses must override this.
     */
    protected abstract void onAccountChanged(Account newAccount, Account oldAccount);

    @Override
    public void onCreate(Bundle savedState) {
        super.onCreate(savedState);

        final Bundle args = getArguments();
        mAccount = args.getParcelable(ARG_ACCOUNT);
        mConversation = args.getParcelable(ARG_CONVERSATION);
        mFolder = args.getParcelable(ARG_FOLDER);

        // Since the uri specified in the conversation base uri may not be unique, we specify a
        // base uri that us guaranteed to be unique for this conversation.
        mBaseUri = "x-thread://" + mAccount.name + "/" + mConversation.id;

        // On JB or newer, we use the 'webkitAnimationStart' DOM event to signal load complete
        // Below JB, try to speed up initial render by having the webview do supplemental draws to
        // custom a software canvas.
        // TODO(mindyp):
        //PAGE READINESS SIGNAL FOR JELLYBEAN AND NEWER
        // Notify the app on 'webkitAnimationStart' of a simple dummy element with a simple no-op
        // animation that immediately runs on page load. The app uses this as a signal that the
        // content is loaded and ready to draw, since WebView delays firing this event until the
        // layers are composited and everything is ready to draw.
        // This signal does not seem to be reliable, so just use the old method for now.
        mEnableContentReadySignal = false; //Utils.isRunningJellybeanOrLater();
        LogUtils.d(LOG_TAG, "onCreate in ConversationViewFragment (this=%s)", this);
        // Not really, we just want to get a crack to store a reference to the change_folder item
        setHasOptionsMenu(true);

        if (savedState != null) {
            mViewState = savedState.getParcelable(BUNDLE_VIEW_STATE);
            mUserVisible = savedState.getBoolean(BUNDLE_USER_VISIBLE);
        } else {
            mViewState = getNewViewState();
        }
    }

    protected abstract WebView getWebView();

    public void instantiateProgressIndicators(View rootView) {
        mBackgroundView = rootView.findViewById(R.id.background_view);
        mProgressView = rootView.findViewById(R.id.loading_progress);
    }

    protected void dismissLoadingStatus() {
        dismissLoadingStatus(null);
    }

    /**
     * Begin the fade-out animation to hide the Progress overlay, either immediately or after some
     * timeout (to ensure that the progress minimum time elapses).
     *
     * @param doAfter an optional Runnable action to execute after the animation completes
     */
    protected void dismissLoadingStatus(final Runnable doAfter) {
        if (mLoadingShownTime == -1) {
            // The runnable hasn't run yet, so just remove it.
            mHandler.removeCallbacks(mDelayedShow);
            dismiss(doAfter);
            return;
        }
        final long diff = Math.abs(System.currentTimeMillis() - mLoadingShownTime);
        if (diff > sMinShowTime) {
            dismiss(doAfter);
        } else {
            mHandler.postDelayed(new FragmentRunnable("dismissLoadingStatus") {
                @Override
                public void go() {
                    dismiss(doAfter);
                }
            }, Math.abs(sMinShowTime - diff));
        }
    }

    private void dismiss(final Runnable doAfter) {
        // Reset loading shown time.
        mLoadingShownTime = -1;
        mProgressView.setVisibility(View.GONE);
        if (mBackgroundView.getVisibility() == View.VISIBLE) {
            animateDismiss(doAfter);
        } else {
            if (doAfter != null) {
                doAfter.run();
            }
        }
    }

    private void animateDismiss(final Runnable doAfter) {
        // the animation can only work (and is only worth doing) if this fragment is added
        // reasons it may not be added: fragment is being destroyed, or in the process of being
        // restored
        if (!isAdded()) {
            mBackgroundView.setVisibility(View.GONE);
            return;
        }

        Utils.enableHardwareLayer(mBackgroundView);
        final Animator animator = AnimatorInflater.loadAnimator(getContext(), R.anim.fade_out);
        animator.setTarget(mBackgroundView);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mBackgroundView.setVisibility(View.GONE);
                mBackgroundView.setLayerType(View.LAYER_TYPE_NONE, null);
                if (doAfter != null) {
                    doAfter.run();
                }
            }
        });
        animator.start();
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        final Activity activity = getActivity();
        if (!(activity instanceof ControllableActivity)) {
            LogUtils.wtf(LOG_TAG, "ConversationViewFragment expects only a ControllableActivity to"
                    + "create it. Cannot proceed.");
        }
        if (activity == null || activity.isFinishing()) {
            // Activity is finishing, just bail.
            return;
        }
        mActivity = (ControllableActivity) activity;
        mContext = activity.getApplicationContext();
        mDateBuilder = new FormattedDateBuilder((Context) mActivity);
        mAccount = mAccountObserver.initialize(mActivity.getAccountController());
    }

    @Override
    public ConversationUpdater getListController() {
        final ControllableActivity activity = (ControllableActivity) getActivity();
        return activity != null ? activity.getConversationUpdater() : null;
    }


    protected void showLoadingStatus() {
        if (!mUserVisible) {
            return;
        }
        if (sMinDelay == -1) {
            Resources res = getContext().getResources();
            sMinDelay = res.getInteger(R.integer.conversationview_show_loading_delay);
            sMinShowTime = res.getInteger(R.integer.conversationview_min_show_loading);
        }
        // If the loading view isn't already showing, show it and remove any
        // pending calls to show the loading screen.
        mBackgroundView.setVisibility(View.VISIBLE);
        mHandler.removeCallbacks(mDelayedShow);
        mHandler.postDelayed(mDelayedShow, sMinDelay);
    }

    public Context getContext() {
        return mContext;
    }

    @Override
    public Conversation getConversation() {
        return mConversation;
    }

    @Override
    public MessageCursor getMessageCursor() {
        return mCursor;
    }

    public Handler getHandler() {
        return mHandler;
    }

    public MessageLoaderCallbacks getMessageLoaderCallbacks() {
        return mMessageLoaderCallbacks;
    }

    public ContactLoaderCallbacks getContactInfoSource() {
        return mContactLoaderCallbacks;
    }

    @Override
    public Account getAccount() {
        return mAccount;
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        mChangeFoldersMenuItem = menu.findItem(R.id.change_folder);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        boolean handled = false;
        switch (item.getItemId()) {
            case R.id.inside_conversation_unread:
                markUnread();
                handled = true;
                break;
        }
        return handled;
    }

    // BEGIN conversation header callbacks
    @Override
    public void onFoldersClicked() {
        if (mChangeFoldersMenuItem == null) {
            LogUtils.e(LOG_TAG, "unable to open 'change folders' dialog for a conversation");
            return;
        }
        mActivity.onOptionsItemSelected(mChangeFoldersMenuItem);
    }

    @Override
    public String getSubjectRemainder(String subject) {
        final SubjectDisplayChanger sdc = mActivity.getSubjectDisplayChanger();
        if (sdc == null) {
            return subject;
        }
        return sdc.getUnshownSubject(subject);
    }
    // END conversation header callbacks

    @Override
    public void onSaveInstanceState(Bundle outState) {
        if (mViewState != null) {
            outState.putParcelable(BUNDLE_VIEW_STATE, mViewState);
        }
        outState.putBoolean(BUNDLE_USER_VISIBLE, mUserVisible);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mAccountObserver.unregisterAndDestroy();
    }

    /**
     * {@link #setUserVisibleHint(boolean)} only works on API >= 15, so implement our own for
     * reliability on older platforms.
     */
    public void setExtraUserVisibleHint(boolean isVisibleToUser) {
        LogUtils.v(LOG_TAG, "in CVF.setHint, val=%s (%s)", isVisibleToUser, this);
        if (mUserVisible != isVisibleToUser) {
            mUserVisible = isVisibleToUser;
            MessageCursor cursor = getMessageCursor();
            if (mUserVisible && (cursor != null && cursor.isLoaded() && cursor.getCount() == 0)) {
                // Pop back to conversation list and show error.
                onError();
                return;
            }
            onUserVisibleHintChanged();
        }
    }

    public boolean isUserVisible() {
        return mUserVisible;
    }

    private class MessageLoaderCallbacks implements LoaderManager.LoaderCallbacks<Cursor> {

        @Override
        public Loader<Cursor> onCreateLoader(int id, Bundle args) {
            return new MessageLoader(mActivity.getActivityContext(), mConversation.messageListUri);
        }

        @Override
        public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
            // ignore truly duplicate results
            // this can happen when restoring after rotation
            if (mCursor == data) {
                return;
            } else {
                final MessageCursor messageCursor = (MessageCursor) data;

                // bind the cursor to this fragment so it can access to the current list controller
                messageCursor.setController(AbstractConversationViewFragment.this);

                if (LogUtils.isLoggable(LOG_TAG, LogUtils.DEBUG)) {
                    LogUtils.d(LOG_TAG, "LOADED CONVERSATION= %s", messageCursor.getDebugDump());
                }

                // We have no messages: exit conversation view.
                if (messageCursor.getCount() == 0
                        && !CursorStatus.isWaitingForResults(messageCursor.getStatus())) {
                    if (mUserVisible) {
                        onError();
                    } else {
                        // we expect that the pager adapter will remove this
                        // conversation fragment on its own due to a separate
                        // conversation cursor update (we might get here if the
                        // message list update fires first. nothing to do
                        // because we expect to be torn down soon.)
                        LogUtils.i(LOG_TAG, "CVF: offscreen conv has no messages, ignoring update"
                                + " in anticipation of conv cursor update. c=%s", mConversation.uri);
                    }
                    // existing mCursor will imminently be closed, must stop referencing it
                    // since we expect to be kicked out soon, it doesn't matter what mCursor
                    // becomes
                    mCursor = null;
                    return;
                }

                // ignore cursors that are still loading results
                if (!messageCursor.isLoaded()) {
                    // existing mCursor will imminently be closed, must stop referencing it
                    // in this case, the new cursor is also no good, and since don't expect to get
                    // here except in initial load situations, it's safest to just ensure the
                    // reference is null
                    mCursor = null;
                    return;
                }
                final MessageCursor oldCursor = mCursor;
                mCursor = messageCursor;
                onMessageCursorLoadFinished(loader, mCursor, oldCursor);
            }
        }

        @Override
        public void onLoaderReset(Loader<Cursor> loader) {
            mCursor = null;
        }

    }

    private void onError() {
        // need to exit this view- conversation may have been
        // deleted, or for whatever reason is now invalid (e.g.
        // discard single draft)
        //
        // N.B. this may involve a fragment transaction, which
        // FragmentManager will refuse to execute directly
        // within onLoadFinished. Make sure the controller knows.
        LogUtils.i(LOG_TAG, "CVF: visible conv has no messages, exiting conv mode");
        // TODO(mindyp): handle ERROR status by showing an error
        // message to the user that there are no messages in
        // this conversation
        mHandler.post(new FragmentRunnable("onError") {

            @Override
            public void go() {
                mActivity.getListHandler()
                .onConversationSelected(null, true /* inLoaderCallbacks */);
            }

        });
    }

    protected void onConversationSeen() {
        // Ignore unsafe calls made after a fragment is detached from an activity
        final ControllableActivity activity = (ControllableActivity) getActivity();
        if (activity == null) {
            LogUtils.w(LOG_TAG, "ignoring onConversationSeen for conv=%s", mConversation.id);
            return;
        }

        mViewState.setInfoForConversation(mConversation);

        // In most circumstances we want to mark the conversation as viewed and read, since the
        // user has read it.  However, if the user has already marked the conversation unread, we
        // do not want a  later mark-read operation to undo this.  So we check this variable which
        // is set in #markUnread() which suppresses automatic mark-read.
        if (!mSuppressMarkingViewed) {
            // mark viewed/read if not previously marked viewed by this conversation view,
            // or if unread messages still exist in the message list cursor
            // we don't want to keep marking viewed on rotation or restore
            // but we do want future re-renders to mark read (e.g. "New message from X" case)
            MessageCursor cursor = getMessageCursor();
            if (!mConversation.isViewed() || (cursor != null && !cursor.isConversationRead())) {
                // Mark the conversation viewed and read.
                activity.getConversationUpdater()
                        .markConversationsRead(Arrays.asList(mConversation), true, true);

                // and update the Message objects in the cursor so the next time a cursor update
                // happens with these messages marked read, we know to ignore it
                if (cursor != null) {
                    cursor.markMessagesRead();
                }
            }
        }
        activity.getListHandler().onConversationSeen(mConversation);

        showAutoFitPrompt();
    }

    protected ConversationViewState getNewViewState() {
        return new ConversationViewState();
    }

    private static class MessageLoader extends CursorLoader {
        private boolean mDeliveredFirstResults = false;

        public MessageLoader(Context c, Uri messageListUri) {
            super(c, messageListUri, UIProvider.MESSAGE_PROJECTION, null, null, null);
        }

        @Override
        public Cursor loadInBackground() {
            return new MessageCursor(super.loadInBackground());
        }

        @Override
        public void deliverResult(Cursor result) {
            // We want to deliver these results, and then we want to make sure
            // that any subsequent
            // queries do not hit the network
            super.deliverResult(result);

            if (!mDeliveredFirstResults) {
                mDeliveredFirstResults = true;
                Uri uri = getUri();

                // Create a ListParams that tells the provider to not hit the
                // network
                final ListParams listParams = new ListParams(ListParams.NO_LIMIT,
                        false /* useNetwork */);

                // Build the new uri with this additional parameter
                uri = uri
                        .buildUpon()
                        .appendQueryParameter(UIProvider.LIST_PARAMS_QUERY_PARAMETER,
                                listParams.serialize()).build();
                setUri(uri);
            }
        }
    }

    /**
     * Inner class to to asynchronously load contact data for all senders in the conversation,
     * and notify observers when the data is ready.
     *
     */
    protected class ContactLoaderCallbacks implements ContactInfoSource,
            LoaderManager.LoaderCallbacks<ImmutableMap<String, ContactInfo>> {

        private Set<String> mSenders;
        private ImmutableMap<String, ContactInfo> mContactInfoMap;
        private DataSetObservable mObservable = new DataSetObservable();

        public void setSenders(Set<String> emailAddresses) {
            mSenders = emailAddresses;
        }

        @Override
        public Loader<ImmutableMap<String, ContactInfo>> onCreateLoader(int id, Bundle args) {
            return new SenderInfoLoader(mActivity.getActivityContext(), mSenders);
        }

        @Override
        public void onLoadFinished(Loader<ImmutableMap<String, ContactInfo>> loader,
                ImmutableMap<String, ContactInfo> data) {
            mContactInfoMap = data;
            mObservable.notifyChanged();
        }

        @Override
        public void onLoaderReset(Loader<ImmutableMap<String, ContactInfo>> loader) {
        }

        @Override
        public ContactInfo getContactInfo(String email) {
            if (mContactInfoMap == null) {
                return null;
            }
            return mContactInfoMap.get(email);
        }

        @Override
        public void registerObserver(DataSetObserver observer) {
            mObservable.registerObserver(observer);
        }

        @Override
        public void unregisterObserver(DataSetObserver observer) {
            mObservable.unregisterObserver(observer);
        }
    }

    protected class AbstractConversationWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            final Activity activity = getActivity();
            if (activity == null) {
                return false;
            }

            boolean result = false;
            final Intent intent;
            Uri uri = Uri.parse(url);
            if (!Utils.isEmpty(mAccount.viewIntentProxyUri)) {
                intent = generateProxyIntent(uri);
            } else {
                intent = new Intent(Intent.ACTION_VIEW, uri);
                intent.putExtra(Browser.EXTRA_APPLICATION_ID, activity.getPackageName());
            }

            try {
                intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
                activity.startActivity(intent);
                result = true;
            } catch (ActivityNotFoundException ex) {
                // If no application can handle the URL, assume that the
                // caller can handle it.
            }

            return result;
        }

        private Intent generateProxyIntent(Uri uri) {
            final Intent intent = new Intent(Intent.ACTION_VIEW, mAccount.viewIntentProxyUri);
            intent.putExtra(UIProvider.ViewProxyExtras.EXTRA_ORIGINAL_URI, uri);
            intent.putExtra(UIProvider.ViewProxyExtras.EXTRA_ACCOUNT, mAccount);

            final Context context = getContext();
            PackageManager manager = null;
            // We need to catch the exception to make CanvasConversationHeaderView
            // test pass.  Bug: http://b/issue?id=3470653.
            try {
                manager = context.getPackageManager();
            } catch (UnsupportedOperationException e) {
                LogUtils.e(LOG_TAG, e, "Error getting package manager");
            }

            if (manager != null) {
                // Try and resolve the intent, to find an activity from this package
                final List<ResolveInfo> resolvedActivities = manager.queryIntentActivities(
                        intent, PackageManager.MATCH_DEFAULT_ONLY);

                final String packageName = context.getPackageName();

                // Now try and find one that came from this package, if one is not found, the UI
                // provider must have specified an intent that is to be handled by a different apk.
                // In that case, the class name will not be set on the intent, so the default
                // intent resolution will be used.
                for (ResolveInfo resolveInfo: resolvedActivities) {
                    final ActivityInfo activityInfo = resolveInfo.activityInfo;
                    if (packageName.equals(activityInfo.packageName)) {
                        intent.setClassName(activityInfo.packageName, activityInfo.name);
                        break;
                    }
                }
            }

            return intent;
        }
    }

    public abstract void onConversationUpdated(Conversation conversation);

    /**
     * Small Runnable-like wrapper that first checks that the Fragment is in a good state before
     * doing any work. Ideal for use with a {@link Handler}.
     */
    protected abstract class FragmentRunnable implements Runnable {

        private final String mOpName;

        public FragmentRunnable(String opName) {
            mOpName = opName;
        }

        public abstract void go();

        @Override
        public void run() {
            if (!isAdded()) {
                LogUtils.i(LOG_TAG, "Unable to run op='%s' b/c fragment is not attached: %s",
                        mOpName, AbstractConversationViewFragment.this);
                return;
            }
            go();
        }

    }

    private static boolean isConversationViewModeSet(final Account acct) {
        return acct.settings.conversationViewMode != UIProvider.ConversationViewMode.UNDEFINED;
    }

    private void showAutoFitPrompt() {
        // If the user has never set a conversation view mode, and they view a wide message, we
        // should prompt them to turn on auto-fit
        final boolean enablePrompt =
                getResources().getInteger(R.integer.prompt_auto_fit_on_first_wide_message) == 1;
        // TODO: Enable this dialog for Email and ensure it saves the setting properly, and remove
        // R.integer.prompt_auto_fit_on_first_wide_message
        if (enablePrompt && isUserVisible() && !isConversationViewModeSet(mAccount)) {
            final boolean isWideContent =
                    getWebView().canScrollHorizontally(1) || getWebView().canScrollHorizontally(-1);

            final boolean dialogShowing =
                    getFragmentManager().findFragmentByTag(AutoFitPromptDialogFragment.FRAGMENT_TAG)
                    != null;

            if (isWideContent && !dialogShowing) {
                // Not everything fits, so let's prompt them to set an auto-fit value
                AutoFitPromptDialogFragment.newInstance(mAccount.updateSettingsUri)
                        .show(getFragmentManager(), AutoFitPromptDialogFragment.FRAGMENT_TAG);
            }
        }
    }
}
