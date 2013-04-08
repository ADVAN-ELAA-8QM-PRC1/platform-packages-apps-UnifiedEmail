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

package com.android.mail.browse;

import android.content.Context;
import android.content.res.Resources;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.OnClickListener;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.android.mail.R;
import com.android.mail.browse.ConversationViewAdapter.ConversationHeaderItem;
import com.android.mail.browse.FolderSpan.FolderSpanDimensions;
import com.android.mail.providers.Conversation;
import com.android.mail.providers.Folder;
import com.android.mail.providers.Settings;
import com.android.mail.ui.FolderDisplayer;
import com.android.mail.utils.LogTag;
import com.android.mail.utils.LogUtils;
import com.android.mail.utils.Utils;

/**
 * A view for the subject and folders in the conversation view. This container
 * makes an attempt to combine subject and folders on the same horizontal line if
 * there is enough room to fit both without wrapping. If they overlap, it
 * adjusts the layout to position the folders below the subject.
 */
public class ConversationViewHeader extends RelativeLayout implements OnClickListener {

    public interface ConversationViewHeaderCallbacks {
        /**
         * Called in response to a click on the folders region.
         */
        void onFoldersClicked();

        /**
         * Called when the height of the {@link ConversationViewHeader} changes.
         *
         * @param newHeight the new height in px
         */
        void onConversationViewHeaderHeightChange(int newHeight);
    }

    private static final String LOG_TAG = LogTag.getLogTag();
    private TextView mSubjectView;
    private FolderSpanTextView mFoldersView;
    private ConversationViewHeaderCallbacks mCallbacks;
    private ConversationAccountController mAccountController;
    private ConversationFolderDisplayer mFolderDisplayer;
    private ConversationHeaderItem mHeaderItem;

    /**
     * Instantiated from this layout: conversation_view_header.xml
     * @param context
     */
    public ConversationViewHeader(Context context) {
        this(context, null);
    }

    public ConversationViewHeader(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mSubjectView = (TextView) findViewById(R.id.subject);
        mFoldersView = (FolderSpanTextView) findViewById(R.id.folders);

        mFoldersView.setOnClickListener(this);
        mFolderDisplayer = new ConversationFolderDisplayer(getContext(), mFoldersView);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        // reposition the folders if they don't fit horizontally next to the
        // subject
        // (taking into account child margins and parent padding)
        final int childWidthSum = getTotalMeasuredChildWidth(mSubjectView)
                + getTotalMeasuredChildWidth(mFoldersView) + getPaddingLeft() + getPaddingRight();

        if (childWidthSum > getMeasuredWidth()) {
            LayoutParams params = (LayoutParams) mFoldersView.getLayoutParams();
            params.addRule(RelativeLayout.BELOW, R.id.subject);
            params.addRule(RelativeLayout.ALIGN_BASELINE, 0);
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    private static int getTotalMeasuredChildWidth(View child) {
        final LayoutParams p = (LayoutParams) child.getLayoutParams();
        return child.getMeasuredWidth() + p.leftMargin + p.rightMargin;
    }

    public void setCallbacks(ConversationViewHeaderCallbacks callbacks,
            ConversationAccountController accountController) {
        mCallbacks = callbacks;
        mAccountController = accountController;
    }

    public void setSubject(final String subject) {
        mSubjectView.setText(subject);
        if (TextUtils.isEmpty(subject)) {
            mSubjectView.setVisibility(GONE);
        }
    }

    public void setFoldersVisible(boolean show) {
        mFoldersView.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    public void setFolders(Conversation conv) {
        setFoldersVisible(true);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        final Settings settings = mAccountController.getAccount().settings;
        if (settings.priorityArrowsEnabled && conv.isImportant()) {
            sb.append('.');
            sb.setSpan(new PriorityIndicatorSpan(getContext(),
                    R.drawable.ic_email_caret_none_important_unread, mFoldersView.getPadding(), 0),
                    0, 1, Spannable.SPAN_INCLUSIVE_EXCLUSIVE);
        }

        mFolderDisplayer.loadConversationFolders(conv, null /* ignoreFolder */,
                -1 /* ignoreFolderType */);
        mFolderDisplayer.appendFolderSpans(sb);

        mFoldersView.setText(sb);
    }

    public void bind(ConversationHeaderItem headerItem) {
        mHeaderItem = headerItem;
    }

    private int measureHeight() {
        ViewGroup parent = (ViewGroup) getParent();
        if (parent == null) {
            LogUtils.e(LOG_TAG, "Unable to measure height of conversation header");
            return getHeight();
        }
        final int h = Utils.measureViewHeight(this, parent);
        return h;
    }

    /**
     * Update the conversation view header to reflect the updated conversation.
     */
    public void onConversationUpdated(Conversation conv) {
        // The only things we have to worry about when the conversation changes
        // in the conversation header are the folders and priority indicators.
        // Updating these will resize the space for the header.
        setFolders(conv);
        if (mHeaderItem != null) {
            final int h = measureHeight();
            if (mHeaderItem.setHeight(h)) {
                mCallbacks.onConversationViewHeaderHeightChange(h);
            }
        }
    }

    @Override
    public void onClick(View v) {
        if (R.id.folders == v.getId()) {
            if (mCallbacks != null) {
                mCallbacks.onFoldersClicked();
            }
        }
    }

    private static class ConversationFolderDisplayer extends FolderDisplayer {

        private FolderSpanDimensions mDims;

        public ConversationFolderDisplayer(Context context, FolderSpanDimensions dims) {
            super(context);
            mDims = dims;
        }

        public void appendFolderSpans(SpannableStringBuilder sb) {
            for (final Folder f : mFoldersSortedSet) {
                final int bgColor = Folder.getNonEmptyColor(f.bgColor, mDefaultBgColor);
                final int fgColor = Folder.getNonEmptyColor(f.fgColor, mDefaultFgColor);
                addSpan(sb, f.name, bgColor, fgColor);
            }

            if (mFoldersSortedSet.isEmpty()) {
                final Resources r = mContext.getResources();
                final String name = r.getString(R.string.add_label);
                final int bgColor = r.getColor(R.color.conv_header_add_label_background);
                final int fgColor = r.getColor(R.color.conv_header_add_label_text);
                addSpan(sb, name, bgColor, fgColor);
            }
        }

        private void addSpan(SpannableStringBuilder sb, String name, int bgColor,
                             int fgColor) {
            final int start = sb.length();
            sb.append(name);
            final int end = sb.length();

            sb.setSpan(new BackgroundColorSpan(bgColor), start, end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(fgColor), start, end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new FolderSpan(sb, mDims), start, end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

    }
}
