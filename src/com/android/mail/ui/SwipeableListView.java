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

import android.content.ContentValues;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.net.Uri;
import android.util.AttributeSet;
import android.widget.AbsListView;
import android.widget.AbsListView.OnScrollListener;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ListView;

import com.android.mail.R;
import com.android.mail.browse.ConversationCursor;
import com.android.mail.browse.ConversationItemView;
import com.android.mail.browse.SwipeableConversationItemView;
import com.android.mail.providers.Conversation;
import com.android.mail.providers.Folder;
import com.android.mail.providers.FolderList;
import com.android.mail.ui.SwipeHelper.Callback;
import com.android.mail.utils.LogTag;
import com.android.mail.utils.LogUtils;
import com.android.mail.utils.Utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;

public class SwipeableListView extends ListView implements Callback, OnScrollListener {
    private SwipeHelper mSwipeHelper;
    private boolean mEnableSwipe = false;

    public static final String LOG_TAG = LogTag.getLogTag();

    private ConversationSelectionSet mConvSelectionSet;
    private int mSwipeAction;
    private Folder mFolder;
    private ListItemSwipedListener mSwipedListener;
    private boolean mScrolling;

    public SwipeableListView(Context context) {
        this(context, null);
    }

    public SwipeableListView(Context context, AttributeSet attrs) {
        this(context, attrs, -1);
    }

    public SwipeableListView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        float densityScale = getResources().getDisplayMetrics().density;
        float pagingTouchSlop = ViewConfiguration.get(context).getScaledPagingTouchSlop();
        mSwipeHelper = new SwipeHelper(context, SwipeHelper.X, this, densityScale,
                pagingTouchSlop);
        setOnScrollListener(this);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        float densityScale = getResources().getDisplayMetrics().density;
        mSwipeHelper.setDensityScale(densityScale);
        float pagingTouchSlop = ViewConfiguration.get(getContext()).getScaledPagingTouchSlop();
        mSwipeHelper.setPagingTouchSlop(pagingTouchSlop);
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        LogUtils.d(Utils.VIEW_DEBUGGING_TAG,
                "START CLF-ListView.onFocusChanged layoutRequested=%s root.layoutRequested=%s",
                isLayoutRequested(), getRootView().isLayoutRequested());
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        LogUtils.d(Utils.VIEW_DEBUGGING_TAG, new Error(),
                "FINISH CLF-ListView.onFocusChanged layoutRequested=%s root.layoutRequested=%s",
                isLayoutRequested(), getRootView().isLayoutRequested());
    }

    /**
     * Enable swipe gestures.
     */
    public void enableSwipe(boolean enable) {
        mEnableSwipe = enable;
    }

    public boolean isSwipeEnabled() {
        return mEnableSwipe;
    }

    public void setSwipeAction(int action) {
        mSwipeAction = action;
    }

    public void setSwipedListener(ListItemSwipedListener listener) {
        mSwipedListener = listener;
    }

    public int getSwipeAction() {
        return mSwipeAction;
    }

    public void setSelectionSet(ConversationSelectionSet set) {
        mConvSelectionSet = set;
    }

    public void setCurrentFolder(Folder folder) {
        mFolder = folder;
    }

    @Override
    public ConversationSelectionSet getSelectionSet() {
        return mConvSelectionSet;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (mScrolling || !mEnableSwipe) {
            return super.onInterceptTouchEvent(ev);
        } else {
            return mSwipeHelper.onInterceptTouchEvent(ev) || super.onInterceptTouchEvent(ev);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (mEnableSwipe) {
            return mSwipeHelper.onTouchEvent(ev) || super.onTouchEvent(ev);
        } else {
            return super.onTouchEvent(ev);
        }
    }

    @Override
    public View getChildAtPosition(MotionEvent ev) {
        // find the view under the pointer, accounting for GONE views
        final int count = getChildCount();
        int touchY = (int) ev.getY();
        int childIdx = 0;
        View slidingChild;
        for (; childIdx < count; childIdx++) {
            slidingChild = getChildAt(childIdx);
            if (slidingChild.getVisibility() == GONE) {
                continue;
            }
            if (touchY >= slidingChild.getTop() && touchY <= slidingChild.getBottom()) {
                if (slidingChild instanceof SwipeableConversationItemView) {
                    return ((SwipeableConversationItemView) slidingChild).getSwipeableItemView();
                }
                return slidingChild;
            }
        }
        return null;
    }

    @Override
    public boolean canChildBeDismissed(SwipeableItemView v) {
        return v.canChildBeDismissed();
    }

    @Override
    public void onChildDismissed(SwipeableItemView v) {
        if (v != null) {
            v.dismiss();
        }
    }

    // Call this whenever a new action is taken; this forces a commit of any
    // existing destructive actions.
    public void commitDestructiveActions(boolean animate) {
        final AnimatedAdapter adapter = getAnimatedAdapter();
        if (adapter != null) {
            adapter.commitLeaveBehindItems(animate);
        }
    }

    public void dismissChild(final ConversationItemView target) {
        final Context context = getContext();
        final ToastBarOperation undoOp;

        undoOp = new ToastBarOperation(1, mSwipeAction, ToastBarOperation.UNDO, false);
        Conversation conv = target.getConversation();
        target.getConversation().position = findConversation(target, conv);
        final AnimatedAdapter adapter = getAnimatedAdapter();
        if (adapter == null) {
            return;
        }
        adapter.setupLeaveBehind(conv, undoOp, conv.position);
        ConversationCursor cc = (ConversationCursor) adapter.getCursor();
        Collection<Conversation> convList = Conversation.listOf(conv);
        ArrayList<Uri> folderUris;
        ArrayList<Boolean> adds;
        switch (mSwipeAction) {
            case R.id.remove_folder:
                FolderOperation folderOp = new FolderOperation(mFolder, false);
                HashMap<Uri, Folder> targetFolders = Folder
                        .hashMapForFolders(conv.getRawFolders());
                targetFolders.remove(folderOp.mFolder.uri);
                final FolderList folders = FolderList.copyOf(targetFolders.values());
                conv.setRawFolders(folders);
                final ContentValues values = new ContentValues();
                folderUris = new ArrayList<Uri>();
                folderUris.add(mFolder.uri);
                adds = new ArrayList<Boolean>();
                adds.add(Boolean.FALSE);
                cc.addFolderUpdates(folderUris, adds, values);
                cc.addTargetFolders(targetFolders.values(), values);
                cc.mostlyDestructiveUpdate(context, Conversation.listOf(conv), values);
                break;
            case R.id.archive:
                cc.mostlyArchive(context, convList);
                break;
            case R.id.delete:
                cc.mostlyDelete(context, convList);
                break;
        }
        if (mSwipedListener != null) {
            mSwipedListener.onListItemSwiped(convList);
        }
        adapter.notifyDataSetChanged();
        if (mConvSelectionSet != null && !mConvSelectionSet.isEmpty()
                && mConvSelectionSet.contains(conv)) {
            mConvSelectionSet.toggle(null, conv);
            // Don't commit destructive actions if the item we just removed from
            // the selection set is the item we just destroyed!
            if (!conv.isMostlyDead() && mConvSelectionSet.isEmpty()) {
                commitDestructiveActions(true);
            }
        }
    }

    @Override
    public void onBeginDrag(View v) {
        // We do this so the underlying ScrollView knows that it won't get
        // the chance to intercept events anymore
        requestDisallowInterceptTouchEvent(true);
        SwipeableConversationItemView view = null;
        if (v instanceof ConversationItemView) {
            view = (SwipeableConversationItemView) v.getParent();
        }
        if (view != null) {
            view.addBackground(getContext());
            view.setBackgroundVisibility(View.VISIBLE);
        }
        cancelDismissCounter();
    }

    @Override
    public void onDragCancelled(SwipeableItemView v) {
        SwipeableConversationItemView view = null;
        if (v instanceof ConversationItemView) {
            view = (SwipeableConversationItemView) ((View) v).getParent();
        }
        if (view != null) {
            view.removeBackground();
        }
        final AnimatedAdapter adapter = getAnimatedAdapter();
        if (adapter != null) {
            adapter.startDismissCounter();
        }
    }

    /**
     * Archive items using the swipe away animation before shrinking them away.
     */
    public void destroyItems(Collection<Conversation> convs,
            final ListItemsRemovedListener listener) {
        if (convs == null) {
            return;
        }
        final AnimatedAdapter adapter = getAnimatedAdapter();
        if (adapter == null) {
            LogUtils.e(LOG_TAG, "SwipeableListView.destroyItems: Cannot destroy: adapter is null.");
            return;
        }
        adapter.swipeDelete(convs, listener);
    }

    public int findConversation(ConversationItemView view, Conversation conv) {
        int position = conv.position;
        long convId = conv.id;
        try {
            if (position == INVALID_POSITION) {
                position = getPositionForView(view);
            }
        } catch (Exception e) {
            position = INVALID_POSITION;
            LogUtils.w(LOG_TAG, "Exception finding position; using alternate strategy");
        }
        if (position == INVALID_POSITION) {
            // Try the other way!
            Conversation foundConv;
            long foundId;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child instanceof SwipeableConversationItemView) {
                    foundConv = ((SwipeableConversationItemView) child).getSwipeableItemView()
                            .getConversation();
                    foundId = foundConv.id;
                    if (foundId == convId) {
                        position = i;
                        break;
                    }
                }
            }
        }
        return position;
    }

    private AnimatedAdapter getAnimatedAdapter() {
        return (AnimatedAdapter) getAdapter();
    }

    @Override
    public boolean performItemClick(View view, int pos, long id) {
        boolean handled = super.performItemClick(view, pos, id);
        // Commit any existing destructive actions when the user selects a
        // conversation to view.
        commitDestructiveActions(true);
        return handled;
    }

    @Override
    public void onScroll() {
        commitDestructiveActions(true);
    }

    public interface ListItemsRemovedListener {
        public void onListItemsRemoved();
    }

    public interface ListItemSwipedListener {
        public void onListItemSwiped(Collection<Conversation> conversations);
    }

    @Override
    public void onScroll(AbsListView arg0, int arg1, int arg2, int arg3) {
        // Do nothing.
    }

    @Override
    public void onScrollStateChanged(AbsListView arg0, int scrollState) {
        switch (scrollState) {
            case OnScrollListener.SCROLL_STATE_IDLE:
                mScrolling = false;
                break;
            default:
                mScrolling = true;
        }
    }

    @Override
    public void cancelDismissCounter() {
        AnimatedAdapter adapter = getAnimatedAdapter();
        if (adapter != null) {
            adapter.cancelDismissCounter();
        }
    }
}
