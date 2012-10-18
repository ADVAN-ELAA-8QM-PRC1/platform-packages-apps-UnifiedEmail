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


import android.content.ContentResolver;
import android.content.Context;
import android.content.Loader;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.DataSetObserver;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnLayoutChangeListener;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import com.android.mail.FormattedDateBuilder;
import com.android.mail.R;
import com.android.mail.browse.ConversationContainer;
import com.android.mail.browse.ConversationContainer.OverlayPosition;
import com.android.mail.browse.ConversationOverlayItem;
import com.android.mail.browse.ConversationViewAdapter;
import com.android.mail.browse.ConversationViewAdapter.ConversationAccountController;
import com.android.mail.browse.ConversationViewAdapter.MessageFooterItem;
import com.android.mail.browse.ConversationViewAdapter.MessageHeaderItem;
import com.android.mail.browse.ConversationViewAdapter.SuperCollapsedBlockItem;
import com.android.mail.browse.ConversationViewHeader;
import com.android.mail.browse.ConversationWebView;
import com.android.mail.browse.ConversationWebView.ContentSizeChangeListener;
import com.android.mail.browse.MessageCursor;
import com.android.mail.browse.MessageCursor.ConversationController;
import com.android.mail.browse.MessageCursor.ConversationMessage;
import com.android.mail.browse.MessageHeaderView;
import com.android.mail.browse.MessageHeaderView.MessageHeaderViewCallbacks;
import com.android.mail.browse.ScrollIndicatorsView;
import com.android.mail.browse.SuperCollapsedBlock;
import com.android.mail.browse.WebViewContextMenu;
import com.android.mail.providers.Account;
import com.android.mail.providers.Address;
import com.android.mail.providers.Conversation;
import com.android.mail.providers.Message;
import com.android.mail.providers.UIProvider;
import com.android.mail.ui.ConversationViewState.ExpansionState;
import com.android.mail.utils.LogTag;
import com.android.mail.utils.LogUtils;
import com.android.mail.utils.Utils;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import java.util.List;
import java.util.Set;


/**
 * The conversation view UI component.
 */
public final class ConversationViewFragment extends AbstractConversationViewFragment implements
        MessageHeaderViewCallbacks,
        SuperCollapsedBlock.OnClickListener,
        ConversationController,
        ConversationAccountController,
        OnLayoutChangeListener {

    private static final String LOG_TAG = LogTag.getLogTag();
    public static final String LAYOUT_TAG = "ConvLayout";

    /**
     * Difference in the height of the message header whose details have been expanded/collapsed
     */
    private int mDiff = 0;

    /**
     * Default value for {@link #mLoadWaitReason}. Conversation load will happen immediately.
     */
    private final int LOAD_NOW = 0;
    /**
     * Value for {@link #mLoadWaitReason} that means we are offscreen and waiting for the visible
     * conversation to finish loading before beginning our load.
     * <p>
     * When this value is set, the fragment should register with {@link ConversationListCallbacks}
     * to know when the visible conversation is loaded. When it is unset, it should unregister.
     */
    private final int LOAD_WAIT_FOR_INITIAL_CONVERSATION = 1;
    /**
     * Value for {@link #mLoadWaitReason} used when a conversation is too heavyweight to load at
     * all when not visible (e.g. requires network fetch, or too complex). Conversation load will
     * wait until this fragment is visible.
     */
    private final int LOAD_WAIT_UNTIL_VISIBLE = 2;

    private ConversationContainer mConversationContainer;

    private ConversationWebView mWebView;

    private ScrollIndicatorsView mScrollIndicators;

    private View mNewMessageBar;

    private HtmlConversationTemplates mTemplates;

    private final MailJsBridge mJsBridge = new MailJsBridge();

    private final WebViewClient mWebViewClient = new ConversationWebViewClient();

    private ConversationViewAdapter mAdapter;

    private boolean mViewsCreated;
    // True if we attempted to render before the views were laid out
    // We will render immediately once layout is done
    private boolean mNeedRender;

    /**
     * Temporary string containing the message bodies of the messages within a super-collapsed
     * block, for one-time use during block expansion. We cannot easily pass the body HTML
     * into JS without problematic escaping, so hold onto it momentarily and signal JS to fetch it
     * using {@link MailJsBridge}.
     */
    private String mTempBodiesHtml;

    private int  mMaxAutoLoadMessages;

    /**
     * If this conversation fragment is not visible, and it's inappropriate to load up front,
     * this is the reason we are waiting. This flag should be cleared once it's okay to load
     * the conversation.
     */
    private int mLoadWaitReason = LOAD_NOW;

    private boolean mEnableContentReadySignal;

    private ContentSizeChangeListener mWebViewSizeChangeListener;

    private float mWebViewYPercent;

    /**
     * Has loadData been called on the WebView yet?
     */
    private boolean mWebViewLoadedData;

    private long mWebViewLoadStartMs;

    private final DataSetObserver mLoadedObserver = new DataSetObserver() {
        @Override
        public void onChanged() {
            getHandler().post(new FragmentRunnable("delayedConversationLoad") {
                @Override
                public void go() {
                    LogUtils.d(LOG_TAG, "CVF load observer fired, this=%s",
                            ConversationViewFragment.this);
                    handleDelayedConversationLoad();
                }
            });
        }
    };

    private final Runnable mOnSeen = new FragmentRunnable("onConversationSeen") {
        @Override
        public void go() {
            if (isUserVisible()) {
                onConversationSeen();
            }
        }
    };

    private static final boolean DEBUG_DUMP_CONVERSATION_HTML = false;
    private static final boolean DISABLE_OFFSCREEN_LOADING = false;

    private static final String BUNDLE_KEY_WEBVIEW_Y_PERCENT =
            ConversationViewFragment.class.getName() + "webview-y-percent";

    /**
     * Constructor needs to be public to handle orientation changes and activity lifecycle events.
     */
    public ConversationViewFragment() {
        super();
    }

    /**
     * Creates a new instance of {@link ConversationViewFragment}, initialized
     * to display a conversation with other parameters inherited/copied from an existing bundle,
     * typically one created using {@link #makeBasicArgs}.
     */
    public static ConversationViewFragment newInstance(Bundle existingArgs,
            Conversation conversation) {
        ConversationViewFragment f = new ConversationViewFragment();
        Bundle args = new Bundle(existingArgs);
        args.putParcelable(ARG_CONVERSATION, conversation);
        f.setArguments(args);
        return f;
    }

    @Override
    public void onAccountChanged(Account newAccount, Account oldAccount) {
        // if overview mode has changed, re-render completely (no need to also update headers)
        if (isOverviewMode(newAccount) != isOverviewMode(oldAccount)) {
            setupOverviewMode();
            final MessageCursor c = getMessageCursor();
            if (c != null) {
                renderConversation(c);
            } else {
                // Null cursor means this fragment is either waiting to load or in the middle of
                // loading. Either way, a future render will happen anyway, and the new setting
                // will take effect when that happens.
            }
            return;
        }

        // settings may have been updated; refresh views that are known to
        // depend on settings
        mConversationContainer.getSnapHeader().onAccountChanged();
        mAdapter.notifyDataSetChanged();
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        LogUtils.d(LOG_TAG, "IN CVF.onActivityCreated, this=%s visible=%s", this, isUserVisible());
        super.onActivityCreated(savedInstanceState);

        if (mActivity == null || mActivity.isFinishing()) {
            // Activity is finishing, just bail.
            return;
        }

        Context context = getContext();
        mTemplates = new HtmlConversationTemplates(context);

        final FormattedDateBuilder dateBuilder = new FormattedDateBuilder(context);

        mAdapter = new ConversationViewAdapter(mActivity, this,
                getLoaderManager(), this, getContactInfoSource(), this,
                this, mAddressCache, dateBuilder);
        mConversationContainer.setOverlayAdapter(mAdapter);

        // set up snap header (the adapter usually does this with the other ones)
        final MessageHeaderView snapHeader = mConversationContainer.getSnapHeader();
        snapHeader.initialize(dateBuilder, this, mAddressCache);
        snapHeader.setCallbacks(this);
        snapHeader.setContactInfoSource(getContactInfoSource());

        mMaxAutoLoadMessages = getResources().getInteger(R.integer.max_auto_load_messages);

        mWebView.setOnCreateContextMenuListener(new WebViewContextMenu(getActivity()));

        // set this up here instead of onCreateView to ensure the latest Account is loaded
        setupOverviewMode();

        // Defer the call to initLoader with a Handler.
        // We want to wait until we know which fragments are present and their final visibility
        // states before going off and doing work. This prevents extraneous loading from occurring
        // as the ViewPager shifts about before the initial position is set.
        //
        // e.g. click on item #10
        // ViewPager.setAdapter() actually first loads #0 and #1 under the assumption that #0 is
        // the initial primary item
        // Then CPC immediately sets the primary item to #10, which tears down #0/#1 and sets up
        // #9/#10/#11.
        getHandler().post(new FragmentRunnable("showConversation") {
            @Override
            public void go() {
                showConversation();
            }
        });

        if (mConversation.conversationBaseUri != null &&
                !Utils.isEmpty(mAccount.accoutCookieQueryUri)) {
            // Set the cookie for this base url
            new SetCookieTask(getContext(), mConversation.conversationBaseUri,
                    mAccount.accoutCookieQueryUri).execute();
        }
    }

    @Override
    public void onCreate(Bundle savedState) {
        super.onCreate(savedState);

        if (savedState != null) {
            mWebViewYPercent = savedState.getFloat(BUNDLE_KEY_WEBVIEW_Y_PERCENT);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater,
            ViewGroup container, Bundle savedInstanceState) {

        View rootView = inflater.inflate(R.layout.conversation_view, container, false);
        mConversationContainer = (ConversationContainer) rootView
                .findViewById(R.id.conversation_container);

        mNewMessageBar = mConversationContainer.findViewById(R.id.new_message_notification_bar);
        mNewMessageBar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onNewMessageBarClick();
            }
        });

        instantiateProgressIndicators(rootView);

        mWebView = (ConversationWebView) mConversationContainer.findViewById(R.id.webview);

        mWebView.addJavascriptInterface(mJsBridge, "mail");
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
        mEnableContentReadySignal = Utils.isRunningJellybeanOrLater();
        mWebView.setUseSoftwareLayer(!mEnableContentReadySignal);
        mWebView.setWebViewClient(mWebViewClient);
        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                LogUtils.i(LOG_TAG, "JS: %s (%s:%d)", consoleMessage.message(),
                        consoleMessage.sourceId(), consoleMessage.lineNumber());
                return true;
            }
        });

        final WebSettings settings = mWebView.getSettings();

        mScrollIndicators = (ScrollIndicatorsView) rootView.findViewById(R.id.scroll_indicators);
        mScrollIndicators.setSourceView(mWebView);

        settings.setJavaScriptEnabled(true);

        final float fontScale = getResources().getConfiguration().fontScale;
        final int desiredFontSizePx = getResources()
                .getInteger(R.integer.conversation_desired_font_size_px);
        final int unstyledFontSizePx = getResources()
                .getInteger(R.integer.conversation_unstyled_font_size_px);

        int textZoom = settings.getTextZoom();
        // apply a correction to the default body text style to get regular text to the size we want
        textZoom = textZoom * desiredFontSizePx / unstyledFontSizePx;
        // then apply any system font scaling
        textZoom = (int) (textZoom * fontScale);
        settings.setTextZoom(textZoom);

        mViewsCreated = true;
        mWebViewLoadedData = false;

        return rootView;
    }

    @Override
    public void onResume() {
        super.onResume();

        // Hacky workaround for http://b/6946182
        Utils.fixSubTreeLayoutIfOrphaned(getView(), "ConversationViewFragment");
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mConversationContainer.setOverlayAdapter(null);
        mAdapter = null;
        resetLoadWaiting(); // be sure to unregister any active load observer
        mViewsCreated = false;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putFloat(BUNDLE_KEY_WEBVIEW_Y_PERCENT, calculateScrollYPercent());
    }

    private float calculateScrollYPercent() {
        float p;
        int scrollY = mWebView.getScrollY();
        int viewH = mWebView.getHeight();
        int webH = (int) (mWebView.getContentHeight() * mWebView.getScale());

        if (webH == 0 || webH <= viewH) {
            p = 0;
        } else if (scrollY + viewH >= webH) {
            // The very bottom is a special case, it acts as a stronger anchor than the scroll top
            // at that point.
            p = 1.0f;
        } else {
            p = (float) scrollY / webH;
        }
        return p;
    }

    private void resetLoadWaiting() {
        if (mLoadWaitReason == LOAD_WAIT_FOR_INITIAL_CONVERSATION) {
            getListController().unregisterConversationLoadedObserver(mLoadedObserver);
        }
        mLoadWaitReason = LOAD_NOW;
    }

    @Override
    protected void markUnread() {
        // Ignore unsafe calls made after a fragment is detached from an activity
        final ControllableActivity activity = (ControllableActivity) getActivity();
        if (activity == null) {
            LogUtils.w(LOG_TAG, "ignoring markUnread for conv=%s", mConversation.id);
            return;
        }

        if (mViewState == null) {
            LogUtils.i(LOG_TAG, "ignoring markUnread for conv with no view state (%d)",
                    mConversation.id);
            return;
        }
        activity.getConversationUpdater().markConversationMessagesUnread(mConversation,
                mViewState.getUnreadMessageUris(), mViewState.getConversationInfo());
    }

    @Override
    public void onUserVisibleHintChanged() {
        final boolean userVisible = isUserVisible();

        if (!userVisible) {
            dismissLoadingStatus();
        } else if (mViewsCreated) {
            if (getMessageCursor() != null) {
                LogUtils.d(LOG_TAG, "Fragment is now user-visible, onConversationSeen: %s", this);
                onConversationSeen();
            } else if (isLoadWaiting()) {
                LogUtils.d(LOG_TAG, "Fragment is now user-visible, showing conversation: %s", this);
                handleDelayedConversationLoad();
            }
        }
    }

    /**
     * Will either call initLoader now to begin loading, or set {@link #mLoadWaitReason} and do
     * nothing (in which case you should later call {@link #handleDelayedConversationLoad()}).
     */
    private void showConversation() {
        final int reason;

        if (isUserVisible()) {
            LogUtils.i(LOG_TAG,
                    "SHOWCONV: CVF is user-visible, immediately loading conversation (%s)", this);
            reason = LOAD_NOW;
        } else {
            final boolean disableOffscreenLoading = DISABLE_OFFSCREEN_LOADING
                    || (mConversation.isRemote
                            || mConversation.getNumMessages() > mMaxAutoLoadMessages);

            // When not visible, we should not immediately load if either this conversation is
            // too heavyweight, or if the main/initial conversation is busy loading.
            if (disableOffscreenLoading) {
                reason = LOAD_WAIT_UNTIL_VISIBLE;
                LogUtils.i(LOG_TAG, "SHOWCONV: CVF waiting until visible to load (%s)", this);
            } else if (getListController().isInitialConversationLoading()) {
                reason = LOAD_WAIT_FOR_INITIAL_CONVERSATION;
                LogUtils.i(LOG_TAG, "SHOWCONV: CVF waiting for initial to finish (%s)", this);
                getListController().registerConversationLoadedObserver(mLoadedObserver);
            } else {
                LogUtils.i(LOG_TAG,
                        "SHOWCONV: CVF is not visible, but no reason to wait. loading now. (%s)",
                        this);
                reason = LOAD_NOW;
            }
        }

        mLoadWaitReason = reason;
        if (mLoadWaitReason == LOAD_NOW) {
            startConversationLoad();
        }
    }

    private void handleDelayedConversationLoad() {
        resetLoadWaiting();
        startConversationLoad();
    }

    private void startConversationLoad() {
        mWebView.setVisibility(View.VISIBLE);
        getLoaderManager().initLoader(MESSAGE_LOADER, Bundle.EMPTY, getMessageLoaderCallbacks());
        if (isUserVisible()) {
            final SubjectDisplayChanger sdc = mActivity.getSubjectDisplayChanger();
            if (sdc != null) {
                sdc.setSubject(mConversation.subject);
            }
        }
        // TODO(mindyp): don't show loading status for a previously rendered
        // conversation. Ielieve this is better done by making sure don't show loading status
        // until XX ms have passed without loading completed.
        showLoadingStatus();
    }

    private void revealConversation() {
        dismissLoadingStatus(mOnSeen);
    }

    private boolean isLoadWaiting() {
        return mLoadWaitReason != LOAD_NOW;
    }

    private void renderConversation(MessageCursor messageCursor) {
        final String convHtml = renderMessageBodies(messageCursor, mEnableContentReadySignal);

        if (DEBUG_DUMP_CONVERSATION_HTML) {
            java.io.FileWriter fw = null;
            try {
                fw = new java.io.FileWriter("/sdcard/conv" + mConversation.id
                        + ".html");
                fw.write(convHtml);
            } catch (java.io.IOException e) {
                e.printStackTrace();
            } finally {
                if (fw != null) {
                    try {
                        fw.close();
                    } catch (java.io.IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        // save off existing scroll position before re-rendering
        if (mWebViewLoadedData) {
            mWebViewYPercent = calculateScrollYPercent();
        }

        mWebView.loadDataWithBaseURL(mBaseUri, convHtml, "text/html", "utf-8", null);
        mWebViewLoadedData = true;
        mWebViewLoadStartMs = SystemClock.uptimeMillis();
    }

    /**
     * Populate the adapter with overlay views (message headers, super-collapsed blocks, a
     * conversation header), and return an HTML document with spacer divs inserted for all overlays.
     *
     */
    private String renderMessageBodies(MessageCursor messageCursor,
            boolean enableContentReadySignal) {
        int pos = -1;

        LogUtils.d(LOG_TAG, "IN renderMessageBodies, fragment=%s", this);
        boolean allowNetworkImages = false;

        // TODO: re-use any existing adapter item state (expanded, details expanded, show pics)

        // Walk through the cursor and build up an overlay adapter as you go.
        // Each overlay has an entry in the adapter for easy scroll handling in the container.
        // Items are not necessarily 1:1 in cursor and adapter because of super-collapsed blocks.
        // When adding adapter items, also add their heights to help the container later determine
        // overlay dimensions.

        // When re-rendering, prevent ConversationContainer from laying out overlays until after
        // the new spacers are positioned by WebView.
        mConversationContainer.invalidateSpacerGeometry();

        mAdapter.clear();

        // re-evaluate the message parts of the view state, since the messages may have changed
        // since the previous render
        final ConversationViewState prevState = mViewState;
        mViewState = new ConversationViewState(prevState);

        // N.B. the units of height for spacers are actually dp and not px because WebView assumes
        // a pixel is an mdpi pixel, unless you set device-dpi.

        // add a single conversation header item
        final int convHeaderPos = mAdapter.addConversationHeader(mConversation);
        final int convHeaderPx = measureOverlayHeight(convHeaderPos);

        final int sideMarginPx = getResources().getDimensionPixelOffset(
                R.dimen.conversation_view_margin_side) + getResources().getDimensionPixelOffset(
                R.dimen.conversation_message_content_margin_side);

        mTemplates.startConversation(mWebView.screenPxToWebPx(sideMarginPx),
                mWebView.screenPxToWebPx(convHeaderPx));

        int collapsedStart = -1;
        ConversationMessage prevCollapsedMsg = null;
        boolean prevSafeForImages = false;

        while (messageCursor.moveToPosition(++pos)) {
            final ConversationMessage msg = messageCursor.getMessage();

            // TODO: save/restore 'show pics' state
            final boolean safeForImages = msg.alwaysShowImages /* || savedStateSaysSafe */;
            allowNetworkImages |= safeForImages;

            final Integer savedExpanded = prevState.getExpansionState(msg);
            final int expandedState;
            if (savedExpanded != null) {
                if (ExpansionState.isSuperCollapsed(savedExpanded) && messageCursor.isLast()) {
                    // override saved state when this is now the new last message
                    // this happens to the second-to-last message when you discard a draft
                    expandedState = ExpansionState.EXPANDED;
                } else {
                    expandedState = savedExpanded;
                }
            } else {
                // new messages that are not expanded default to being eligible for super-collapse
                expandedState = (!msg.read || msg.starred || messageCursor.isLast()) ?
                        ExpansionState.EXPANDED : ExpansionState.SUPER_COLLAPSED;
            }
            mViewState.setExpansionState(msg, expandedState);

            // save off "read" state from the cursor
            // later, the view may not match the cursor (e.g. conversation marked read on open)
            // however, if a previous state indicated this message was unread, trust that instead
            // so "mark unread" marks all originally unread messages
            mViewState.setReadState(msg, msg.read && !prevState.isUnread(msg));

            // We only want to consider this for inclusion in the super collapsed block if
            // 1) The we don't have previous state about this message  (The first time that the
            //    user opens a conversation)
            // 2) The previously saved state for this message indicates that this message is
            //    in the super collapsed block.
            if (ExpansionState.isSuperCollapsed(expandedState)) {
                // contribute to a super-collapsed block that will be emitted just before the
                // next expanded header
                if (collapsedStart < 0) {
                    collapsedStart = pos;
                }
                prevCollapsedMsg = msg;
                prevSafeForImages = safeForImages;
                continue;
            }

            // resolve any deferred decisions on previous collapsed items
            if (collapsedStart >= 0) {
                if (pos - collapsedStart == 1) {
                    // special-case for a single collapsed message: no need to super-collapse it
                    renderMessage(prevCollapsedMsg, false /* expanded */,
                            prevSafeForImages);
                } else {
                    renderSuperCollapsedBlock(collapsedStart, pos - 1);
                }
                prevCollapsedMsg = null;
                collapsedStart = -1;
            }

            renderMessage(msg, ExpansionState.isExpanded(expandedState), safeForImages);
        }

        mWebView.getSettings().setBlockNetworkImage(!allowNetworkImages);

        // If the conversation has specified a base uri, use it here, use mBaseUri
        final String conversationBaseUri = mConversation.conversationBaseUri != null ?
                mConversation.conversationBaseUri.toString() : mBaseUri;
        return mTemplates.endConversation(mBaseUri, conversationBaseUri, 320,
                mWebView.getViewportWidth(), enableContentReadySignal, isOverviewMode(mAccount));
    }

    private void renderSuperCollapsedBlock(int start, int end) {
        final int blockPos = mAdapter.addSuperCollapsedBlock(start, end);
        final int blockPx = measureOverlayHeight(blockPos);
        mTemplates.appendSuperCollapsedHtml(start, mWebView.screenPxToWebPx(blockPx));
    }

    private void renderMessage(ConversationMessage msg, boolean expanded,
            boolean safeForImages) {
        final int headerPos = mAdapter.addMessageHeader(msg, expanded);
        final MessageHeaderItem headerItem = (MessageHeaderItem) mAdapter.getItem(headerPos);

        final int footerPos = mAdapter.addMessageFooter(headerItem);

        // Measure item header and footer heights to allocate spacers in HTML
        // But since the views themselves don't exist yet, render each item temporarily into
        // a host view for measurement.
        final int headerPx = measureOverlayHeight(headerPos);
        final int footerPx = measureOverlayHeight(footerPos);

        mTemplates.appendMessageHtml(msg, expanded, safeForImages,
                mWebView.screenPxToWebPx(headerPx), mWebView.screenPxToWebPx(footerPx));
    }

    private String renderCollapsedHeaders(MessageCursor cursor,
            SuperCollapsedBlockItem blockToReplace) {
        final List<ConversationOverlayItem> replacements = Lists.newArrayList();

        mTemplates.reset();

        // In devices with non-integral density multiplier, screen pixels translate to non-integral
        // web pixels. Keep track of the error that occurs when we cast all heights to int
        float error = 0f;
        for (int i = blockToReplace.getStart(), end = blockToReplace.getEnd(); i <= end; i++) {
            cursor.moveToPosition(i);
            final ConversationMessage msg = cursor.getMessage();
            final MessageHeaderItem header = mAdapter.newMessageHeaderItem(msg,
                    false /* expanded */);
            final MessageFooterItem footer = mAdapter.newMessageFooterItem(header);

            final int headerPx = measureOverlayHeight(header);
            final int footerPx = measureOverlayHeight(footer);
            error += mWebView.screenPxToWebPxError(headerPx)
                    + mWebView.screenPxToWebPxError(footerPx);

            // When the error becomes greater than 1 pixel, make the next header 1 pixel taller
            int correction = 0;
            if (error >= 1) {
                correction = 1;
                error -= 1;
            }

            mTemplates.appendMessageHtml(msg, false /* expanded */, msg.alwaysShowImages,
                    mWebView.screenPxToWebPx(headerPx) + correction,
                    mWebView.screenPxToWebPx(footerPx));
            replacements.add(header);
            replacements.add(footer);

            mViewState.setExpansionState(msg, ExpansionState.COLLAPSED);
        }

        mAdapter.replaceSuperCollapsedBlock(blockToReplace, replacements);

        return mTemplates.emit();
    }

    private int measureOverlayHeight(int position) {
        return measureOverlayHeight(mAdapter.getItem(position));
    }

    /**
     * Measure the height of an adapter view by rendering an adapter item into a temporary
     * host view, and asking the view to immediately measure itself. This method will reuse
     * a previous adapter view from {@link ConversationContainer}'s scrap views if one was generated
     * earlier.
     * <p>
     * After measuring the height, this method also saves the height in the
     * {@link ConversationOverlayItem} for later use in overlay positioning.
     *
     * @param convItem adapter item with data to render and measure
     * @return height of the rendered view in screen px
     */
    private int measureOverlayHeight(ConversationOverlayItem convItem) {
        final int type = convItem.getType();

        final View convertView = mConversationContainer.getScrapView(type);
        final View hostView = mAdapter.getView(convItem, convertView, mConversationContainer,
                true /* measureOnly */);
        if (convertView == null) {
            mConversationContainer.addScrapView(type, hostView);
        }

        final int heightPx = mConversationContainer.measureOverlay(hostView);
        convItem.setHeight(heightPx);
        convItem.markMeasurementValid();

        return heightPx;
    }

    @Override
    public void onConversationViewHeaderHeightChange(int newHeight) {
        final int h = mWebView.screenPxToWebPx(newHeight);

        mWebView.loadUrl(String.format("javascript:setConversationHeaderSpacerHeight(%s);", h));
    }

    // END conversation header callbacks

    // START message header callbacks
    @Override
    public void setMessageSpacerHeight(MessageHeaderItem item, int newSpacerHeightPx) {
        mConversationContainer.invalidateSpacerGeometry();

        // update message HTML spacer height
        final int h = mWebView.screenPxToWebPx(newSpacerHeightPx);
        LogUtils.i(LAYOUT_TAG, "setting HTML spacer h=%dwebPx (%dscreenPx)", h,
                newSpacerHeightPx);
        mWebView.loadUrl(String.format("javascript:setMessageHeaderSpacerHeight('%s', %s);",
                mTemplates.getMessageDomId(item.getMessage()), h));
    }

    @Override
    public void setMessageExpanded(MessageHeaderItem item, int newSpacerHeightPx) {
        mConversationContainer.invalidateSpacerGeometry();

        // show/hide the HTML message body and update the spacer height
        final int h = mWebView.screenPxToWebPx(newSpacerHeightPx);
        LogUtils.i(LAYOUT_TAG, "setting HTML spacer expanded=%s h=%dwebPx (%dscreenPx)",
                item.isExpanded(), h, newSpacerHeightPx);
        mWebView.loadUrl(String.format("javascript:setMessageBodyVisible('%s', %s, %s);",
                mTemplates.getMessageDomId(item.getMessage()), item.isExpanded(), h));

        mViewState.setExpansionState(item.getMessage(),
                item.isExpanded() ? ExpansionState.EXPANDED : ExpansionState.COLLAPSED);
    }

    @Override
    public void showExternalResources(Message msg) {
        mWebView.getSettings().setBlockNetworkImage(false);
        mWebView.loadUrl("javascript:unblockImages('" + mTemplates.getMessageDomId(msg) + "');");
    }
    // END message header callbacks

    @Override
    public void onSuperCollapsedClick(SuperCollapsedBlockItem item) {
        MessageCursor cursor = getMessageCursor();
        if (cursor == null || !mViewsCreated) {
            return;
        }

        mTempBodiesHtml = renderCollapsedHeaders(cursor, item);
        mWebView.loadUrl("javascript:replaceSuperCollapsedBlock(" + item.getStart() + ")");
    }

    private void showNewMessageNotification(NewMessagesInfo info) {
        final TextView descriptionView = (TextView) mNewMessageBar.findViewById(
                R.id.new_message_description);
        descriptionView.setText(info.getNotificationText());
        mNewMessageBar.setVisibility(View.VISIBLE);
    }

    private void onNewMessageBarClick() {
        mNewMessageBar.setVisibility(View.GONE);

        renderConversation(getMessageCursor()); // mCursor is already up-to-date
                                                // per onLoadFinished()
    }

    private static OverlayPosition[] parsePositions(final String[] topArray,
            final String[] bottomArray) {
        final int len = topArray.length;
        final OverlayPosition[] positions = new OverlayPosition[len];
        for (int i = 0; i < len; i++) {
            positions[i] = new OverlayPosition(
                    Integer.parseInt(topArray[i]), Integer.parseInt(bottomArray[i]));
        }
        return positions;
    }

    @Override
    public String toString() {
        // log extra info at DEBUG level or finer
        final String s = super.toString();
        if (!LogUtils.isLoggable(LOG_TAG, LogUtils.DEBUG) || mConversation == null) {
            return s;
        }
        return "(" + s + " subj=" + mConversation.subject + ")";
    }

    private Address getAddress(String rawFrom) {
        Address addr = mAddressCache.get(rawFrom);
        if (addr == null) {
            addr = Address.getEmailAddress(rawFrom);
            mAddressCache.put(rawFrom, addr);
        }
        return addr;
    }

    private void ensureContentSizeChangeListener() {
        if (mWebViewSizeChangeListener == null) {
            mWebViewSizeChangeListener = new ConversationWebView.ContentSizeChangeListener() {
                @Override
                public void onHeightChange(int h) {
                    // When WebKit says the DOM height has changed, re-measure
                    // bodies and re-position their headers.
                    // This is separate from the typical JavaScript DOM change
                    // listeners because cases like NARROW_COLUMNS text reflow do not trigger DOM
                    // events.
                    mWebView.loadUrl("javascript:measurePositions();");
                }
            };
        }
        mWebView.setContentSizeChangeListener(mWebViewSizeChangeListener);
    }

    private static boolean isOverviewMode(Account acct) {
        return acct.settings.conversationViewMode == UIProvider.ConversationViewMode.OVERVIEW;
    }

    private void setupOverviewMode() {
        final boolean overviewMode = isOverviewMode(mAccount);
        final WebSettings settings = mWebView.getSettings();
        settings.setUseWideViewPort(overviewMode);
        settings.setSupportZoom(overviewMode);
        if (overviewMode) {
            settings.setBuiltInZoomControls(true);
            settings.setDisplayZoomControls(false);
        }
    }

    private class ConversationWebViewClient extends AbstractConversationWebViewClient {
        @Override
        public void onPageFinished(WebView view, String url) {
            // Ignore unsafe calls made after a fragment is detached from an activity
            final ControllableActivity activity = (ControllableActivity) getActivity();
            if (activity == null || !mViewsCreated) {
                LogUtils.i(LOG_TAG, "ignoring CVF.onPageFinished, url=%s fragment=%s", url,
                        ConversationViewFragment.this);
                return;
            }

            LogUtils.i(LOG_TAG, "IN CVF.onPageFinished, url=%s fragment=%s t=%sms", url,
                    ConversationViewFragment.this,
                    (SystemClock.uptimeMillis() - mWebViewLoadStartMs));

            super.onPageFinished(view, url);

            ensureContentSizeChangeListener();

            if (!mEnableContentReadySignal) {
                revealConversation();
            }

            // We are not able to use the loader manager unless this fragment is added to the
            // activity
            if (isAdded()) {
                final Set<String> emailAddresses = Sets.newHashSet();
                for (Address addr : mAddressCache.values()) {
                    emailAddresses.add(addr.getAddress());
                }
                ContactLoaderCallbacks callbacks = getContactInfoSource();
                getContactInfoSource().setSenders(emailAddresses);
                getLoaderManager().restartLoader(CONTACT_LOADER, Bundle.EMPTY, callbacks);
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return mViewsCreated && super.shouldOverrideUrlLoading(view, url);
        }
    }

    /**
     * NOTE: all public methods must be listed in the proguard flags so that they can be accessed
     * via reflection and not stripped.
     *
     */
    // TODO: switch these Runnables to FragmentRunnables?
    private class MailJsBridge {

        @SuppressWarnings("unused")
        @JavascriptInterface
        public void onWebContentGeometryChange(final String[] overlayTopStrs,
                final String[] overlayBottomStrs) {
            try {
                getHandler().post(new Runnable() {

                    @Override
                    public void run() {
                        if (!mViewsCreated) {
                            LogUtils.d(LOG_TAG, "ignoring webContentGeometryChange because views"
                                    + " are gone, %s", ConversationViewFragment.this);
                            return;
                        }
                        mConversationContainer.onGeometryChange(
                                parsePositions(overlayTopStrs, overlayBottomStrs));
                        if (mDiff != 0) {
                            // SCROLL!
                            int scale = (int) (mWebView.getScale() / mWebView.getInitialScale());
                            if (scale > 1) {
                                mWebView.scrollBy(0, (mDiff * (scale - 1)));
                            }
                            mDiff = 0;
                        }
                    }
                });
            } catch (Throwable t) {
                LogUtils.e(LOG_TAG, t, "Error in MailJsBridge.onWebContentGeometryChange");
            }
        }

        @SuppressWarnings("unused")
        @JavascriptInterface
        public String getTempMessageBodies() {
            try {
                if (!mViewsCreated) {
                    return "";
                }

                final String s = mTempBodiesHtml;
                mTempBodiesHtml = null;
                return s;
            } catch (Throwable t) {
                LogUtils.e(LOG_TAG, t, "Error in MailJsBridge.getTempMessageBodies");
                return "";
            }
        }

        @SuppressWarnings("unused")
        @JavascriptInterface
        public String getMessageBody(String domId) {
            try {
                final MessageCursor cursor = getMessageCursor();
                if (!mViewsCreated || cursor == null) {
                    return "";
                }

                int pos = -1;
                while (cursor.moveToPosition(++pos)) {
                    final ConversationMessage msg = cursor.getMessage();
                    if (TextUtils.equals(domId, mTemplates.getMessageDomId(msg))) {
                        return msg.getBodyAsHtml();
                    }
                }

                return "";

            } catch (Throwable t) {
                LogUtils.e(LOG_TAG, t, "Error in MailJsBridge.getMessageBody");
                return "";
            }
        }

        @SuppressWarnings("unused")
        @JavascriptInterface
        public void onContentReady() {
            try {
                getHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        if (mWebViewLoadStartMs != 0) {
                            LogUtils.i(LOG_TAG, "IN CVF.onContentReady, f=%s vis=%s t=%sms",
                                    ConversationViewFragment.this,
                                    isUserVisible(),
                                    (SystemClock.uptimeMillis() - mWebViewLoadStartMs));
                        }
                        revealConversation();
                    }
                });
            } catch (Throwable t) {
                LogUtils.e(LOG_TAG, t, "Error in MailJsBridge.onContentReady");
                // Still try to show the conversation.
                revealConversation();
            }
        }

        @SuppressWarnings("unused")
        @JavascriptInterface
        public float getScrollYPercent() {
            try {
                return mWebViewYPercent;
            } catch (Throwable t) {
                LogUtils.e(LOG_TAG, t, "Error in MailJsBridge.getScrollYPercent");
                return 0f;
            }
        }
    }

    private class NewMessagesInfo {
        int count;
        String senderAddress;

        /**
         * Return the display text for the new message notification overlay. It will be formatted
         * appropriately for a single new message vs. multiple new messages.
         *
         * @return display text
         */
        public String getNotificationText() {
            Resources res = getResources();
            if (count > 1) {
                return res.getString(R.string.new_incoming_messages_many, count);
            } else {
                final Address addr = getAddress(senderAddress);
                return res.getString(R.string.new_incoming_messages_one,
                        TextUtils.isEmpty(addr.getName()) ? addr.getAddress() : addr.getName());
            }
        }
    }

    @Override
    public void onMessageCursorLoadFinished(Loader<Cursor> loader, MessageCursor newCursor,
            MessageCursor oldCursor) {
        /*
         * what kind of changes affect the MessageCursor? 1. new message(s) 2.
         * read/unread state change 3. deleted message, either regular or draft
         * 4. updated message, either from self or from others, updated in
         * content or state or sender 5. star/unstar of message (technically
         * similar to #1) 6. other label change Use MessageCursor.hashCode() to
         * sort out interesting vs. no-op cursor updates.
         */
        final boolean changed = newCursor != null && oldCursor != null
                && newCursor.getStateHashCode() != oldCursor.getStateHashCode();

        if (oldCursor != null) {
            final NewMessagesInfo info = getNewIncomingMessagesInfo(newCursor);

            if (info.count > 0) {
                // don't immediately render new incoming messages from other
                // senders
                // (to avoid a new message from losing the user's focus)
                LogUtils.i(LOG_TAG, "CONV RENDER: conversation updated"
                        + ", holding cursor for new incoming message (%s)", this);
                showNewMessageNotification(info);
                return;
            }

            if (!changed) {
                final boolean processedInPlace = processInPlaceUpdates(newCursor, oldCursor);
                if (processedInPlace) {
                    LogUtils.i(LOG_TAG, "CONV RENDER: processed update(s) in place (%s)", this);
                } else {
                    LogUtils.i(LOG_TAG, "CONV RENDER: uninteresting update"
                            + ", ignoring this conversation update (%s)", this);
                }
                return;
            }
            // cursors are different, and not due to an incoming message. fall
            // through and render.
            LogUtils.i(LOG_TAG, "CONV RENDER: conversation updated"
                    + ", but not due to incoming message. rendering. (%s)", this);
        } else {
            LogUtils.i(LOG_TAG, "CONV RENDER: initial render. (%s)", this);
        }

        // if layout hasn't happened, delay render
        // This is needed in addition to the showConversation() delay to speed
        // up rotation and restoration.
        if (mConversationContainer.getWidth() == 0) {
            mNeedRender = true;
            mConversationContainer.addOnLayoutChangeListener(this);
        } else {
            renderConversation(newCursor);
        }
    }

    private NewMessagesInfo getNewIncomingMessagesInfo(MessageCursor newCursor) {
        final NewMessagesInfo info = new NewMessagesInfo();

        int pos = -1;
        while (newCursor.moveToPosition(++pos)) {
            final Message m = newCursor.getMessage();
            if (!mViewState.contains(m)) {
                LogUtils.i(LOG_TAG, "conversation diff: found new msg: %s", m.uri);

                final Address from = getAddress(m.from);
                // distinguish ours from theirs
                // new messages from the account owner should not trigger a
                // notification
                if (mAccount.ownsFromAddress(from.getAddress())) {
                    LogUtils.i(LOG_TAG, "found message from self: %s", m.uri);
                    continue;
                }

                info.count++;
                info.senderAddress = m.from;
            }
        }
        return info;
    }

    private boolean processInPlaceUpdates(MessageCursor newCursor, MessageCursor oldCursor) {
        final Set<String> idsOfChangedBodies = Sets.newHashSet();
        boolean changed = false;

        int pos = 0;
        while (true) {
            if (!newCursor.moveToPosition(pos) || !oldCursor.moveToPosition(pos)) {
                break;
            }

            final ConversationMessage newMsg = newCursor.getMessage();
            final ConversationMessage oldMsg = oldCursor.getMessage();

            if (!TextUtils.equals(newMsg.from, oldMsg.from)) {
                mAdapter.updateItemsForMessage(newMsg);
                LogUtils.i(LOG_TAG, "msg #%d (%d): detected sender change", pos, newMsg.id);
                changed = true;
            }

            // update changed message bodies in-place
            if (!TextUtils.equals(newMsg.bodyHtml, oldMsg.bodyHtml) ||
                    !TextUtils.equals(newMsg.bodyText, oldMsg.bodyText)) {
                // maybe just set a flag to notify JS to re-request changed bodies
                idsOfChangedBodies.add('"' + mTemplates.getMessageDomId(newMsg) + '"');
                LogUtils.i(LOG_TAG, "msg #%d (%d): detected body change", pos, newMsg.id);
            }

            pos++;
        }

        if (!idsOfChangedBodies.isEmpty()) {
            mWebView.loadUrl(String.format("javascript:replaceMessageBodies([%s]);",
                    TextUtils.join(",", idsOfChangedBodies)));
            changed = true;
        }

        return changed;
    }

    private class SetCookieTask extends AsyncTask<Void, Void, Void> {
        final String mUri;
        final Uri mAccountCookieQueryUri;
        final ContentResolver mResolver;

        SetCookieTask(Context context, Uri baseUri, Uri accountCookieQueryUri) {
            mUri = baseUri.toString();
            mAccountCookieQueryUri = accountCookieQueryUri;
            mResolver = context.getContentResolver();
        }

        @Override
        public Void doInBackground(Void... args) {
            // First query for the coookie string from the UI provider
            final Cursor cookieCursor = mResolver.query(mAccountCookieQueryUri,
                    UIProvider.ACCOUNT_COOKIE_PROJECTION, null, null, null);
            if (cookieCursor == null) {
                return null;
            }

            try {
                if (cookieCursor.moveToFirst()) {
                    final String cookie = cookieCursor.getString(
                            cookieCursor.getColumnIndex(UIProvider.AccountCookieColumns.COOKIE));

                    if (cookie != null) {
                        final CookieSyncManager csm =
                            CookieSyncManager.createInstance(getContext());
                        CookieManager.getInstance().setCookie(mUri, cookie);
                        csm.sync();
                    }
                }

            } finally {
                cookieCursor.close();
            }


            return null;
        }
    }

    @Override
    public void onConversationUpdated(Conversation conv) {
        final ConversationViewHeader headerView = (ConversationViewHeader) mConversationContainer
                .findViewById(R.id.conversation_header);
        mConversation = conv;
        if (headerView != null) {
            headerView.onConversationUpdated(conv);
        }
    }

    @Override
    public void onLayoutChange(View v, int left, int top, int right,
            int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
        boolean sizeChanged = mNeedRender
                && mConversationContainer.getWidth() != 0;
        if (sizeChanged) {
            mNeedRender = false;
            mConversationContainer.removeOnLayoutChangeListener(this);
            renderConversation(getMessageCursor());
        }
    }

    @Override
    public void setMessageDetailsExpanded(MessageHeaderItem i, boolean expanded,
            int heightBefore) {
        mDiff = (expanded ? 1 : -1) * Math.abs(i.getHeight() - heightBefore);
    }
}
