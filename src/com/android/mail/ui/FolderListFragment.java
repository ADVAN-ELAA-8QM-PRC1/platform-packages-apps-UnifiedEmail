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

import android.app.Activity;
import android.app.ListFragment;
import android.app.LoaderManager;
import android.content.CursorLoader;
import android.content.Loader;
import android.database.Cursor;
import android.database.DataSetObserver;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.android.mail.R;
import com.android.mail.providers.Folder;
import com.android.mail.providers.RecentFolderObserver;
import com.android.mail.providers.UIProvider;
import com.android.mail.providers.UIProvider.FolderType;
import com.android.mail.utils.LogTag;
import com.android.mail.utils.LogUtils;
import com.android.mail.utils.Utils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The folder list UI component.
 */
public final class FolderListFragment extends ListFragment implements
        LoaderManager.LoaderCallbacks<Cursor> {
    private static final String LOG_TAG = LogTag.getLogTag();
    /** The parent activity */
    private ControllableActivity mActivity;
    /** The underlying list view */
    private ListView mListView;
    /** URI that points to the list of folders for the current account. */
    private Uri mFolderListUri;
    /** True if you want a sectioned FolderList, false otherwise. */
    private boolean mIsSectioned;
    /** An {@link ArrayList} of {@link FolderType}s to exclude from displaying. */
    private ArrayList<Integer> mExcludedFolderTypes;
    /** Callback into the parent */
    private FolderListSelectionListener mListener;

    /** The currently selected folder (the folder being viewed).  This is never null. */
    private Uri mSelectedFolderUri = Uri.EMPTY;
    /** Parent of the current folder, or null if the current folder is not a child. */
    private Folder mParentFolder;

    private static final int FOLDER_LOADER_ID = 0;
    public static final int MODE_DEFAULT = 0;
    public static final int MODE_PICK = 1;
    /** Key to store {@link #mParentFolder}. */
    private static final String ARG_PARENT_FOLDER = "arg-parent-folder";
    /** Key to store {@link #mFolderListUri}. */
    private static final String ARG_FOLDER_URI = "arg-folder-list-uri";
    /** Key to store {@link #mIsSectioned} */
    private static final String ARG_IS_SECTIONED = "arg-is-sectioned";
    /** Key to store {@link #mExcludedFolderTypes} */
    private static final String ARG_EXCLUDED_FOLDER_TYPES = "arg-excluded-folder-types";

    private static final String BUNDLE_LIST_STATE = "flf-list-state";
    private static final String BUNDLE_SELECTED_FOLDER = "flf-selected-folder";
    private static final String BUNDLE_SELECTED_TYPE = "flf-selected-type";

    private FolderListFragmentCursorAdapter mCursorAdapter;
    /** View that we show while we are waiting for the folder list to load */
    private View mEmptyView;
    /** Observer to wait for changes to the current folder so we can change the selected folder */
    private FolderObserver mFolderObserver = null;
    /**
     * Type of currently selected folder: {@link FolderListAdapter.Item#FOLDER_SYSTEM},
     * {@link FolderListAdapter.Item#FOLDER_RECENT} or {@link FolderListAdapter.Item#FOLDER_USER}.
     */
    // Setting to NOT_A_FOLDER = leaving uninitialized.
    private int mSelectedFolderType = FolderListAdapter.Item.NOT_A_FOLDER;
    private Cursor mFutureData;
    private ConversationListCallbacks mConversationListCallback;

    /**
     * Listens to folder changes from the controller and updates state accordingly.
     */
    private final class FolderObserver extends DataSetObserver {
        @Override
        public void onChanged() {
            if (mActivity == null) {
                return;
            }
            final FolderController controller = mActivity.getFolderController();
            if (controller == null) {
                return;
            }
            setSelectedFolder(controller.getFolder());
        }
    }

    /**
     * Constructor needs to be public to handle orientation changes and activity lifecycle events.
     */
    public FolderListFragment() {
        super();
    }

    @Override
    public void onResume() {

        super.onResume();
        // Hacky workaround for http://b/6946182
        Utils.fixSubTreeLayoutIfOrphaned(getView(), "FolderListFragment");
    }
    /**
     * Creates a new instance of {@link ConversationListFragment}, initialized
     * to display conversation list context.
     * @param isSectioned TODO(viki):
     */
    public static FolderListFragment newInstance(Folder parentFolder, Uri folderUri,
            boolean isSectioned) {
        return newInstance(parentFolder, folderUri, isSectioned, null);
    }

    /**
     * Creates a new instance of {@link ConversationListFragment}, initialized
     * to display conversation list context.
     * @param isSectioned TODO(viki):
     * @param excludedFolderTypes A list of {@link FolderType}s to exclude from displaying
     */
    public static FolderListFragment newInstance(Folder parentFolder, Uri folderUri,
            boolean isSectioned, final ArrayList<Integer> excludedFolderTypes) {
        final FolderListFragment fragment = new FolderListFragment();
        final Bundle args = new Bundle();
        if (parentFolder != null) {
            args.putParcelable(ARG_PARENT_FOLDER, parentFolder);
        }
        args.putString(ARG_FOLDER_URI, folderUri.toString());
        args.putBoolean(ARG_IS_SECTIONED, isSectioned);
        if (excludedFolderTypes != null) {
            args.putIntegerArrayList(ARG_EXCLUDED_FOLDER_TYPES, excludedFolderTypes);
        }
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onActivityCreated(Bundle savedState) {
        super.onActivityCreated(savedState);
        // Strictly speaking, we get back an android.app.Activity from getActivity. However, the
        // only activity creating a ConversationListContext is a MailActivity which is of type
        // ControllableActivity, so this cast should be safe. If this cast fails, some other
        // activity is creating ConversationListFragments. This activity must be of type
        // ControllableActivity.
        final Activity activity = getActivity();
        if (! (activity instanceof ControllableActivity)){
            LogUtils.wtf(LOG_TAG, "FolderListFragment expects only a ControllableActivity to" +
                    "create it. Cannot proceed.");
        }
        mActivity = (ControllableActivity) activity;
        mConversationListCallback = mActivity.getListHandler();
        final FolderController controller = mActivity.getFolderController();
        // Listen to folder changes in the future
        mFolderObserver = new FolderObserver();
        if (controller != null) {
            // Only register for selected folder updates if we have a controller.
            controller.registerFolderObserver(mFolderObserver);
        }

        mListener = mActivity.getFolderListSelectionListener();
        if (mActivity.isFinishing()) {
            // Activity is finishing, just bail.
            return;
        }

        final Folder selectedFolder;
        if (mParentFolder != null) {
            mCursorAdapter = new HierarchicalFolderListAdapter(null, mParentFolder);
            selectedFolder = mActivity.getHierarchyFolder();
        } else {
            mCursorAdapter = new FolderListAdapter(R.layout.folder_item, mIsSectioned);
            selectedFolder = controller == null ? null : controller.getFolder();
        }
        // Is the selected folder fresher than the one we have restored from a bundle?
        if (selectedFolder != null && !selectedFolder.uri.equals(mSelectedFolderUri)) {
            setSelectedFolder(selectedFolder);
        }
        setListAdapter(mCursorAdapter);
        // Set the region which gets highlighted since it might not have been set till now.
        getLoaderManager().initLoader(FOLDER_LOADER_ID, Bundle.EMPTY, this);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedState) {
        final Bundle args = getArguments();
        mFolderListUri = Uri.parse(args.getString(ARG_FOLDER_URI));
        mParentFolder = (Folder) args.getParcelable(ARG_PARENT_FOLDER);
        mIsSectioned = args.getBoolean(ARG_IS_SECTIONED);
        mExcludedFolderTypes = args.getIntegerArrayList(ARG_EXCLUDED_FOLDER_TYPES);
        final View rootView = inflater.inflate(R.layout.folder_list, null);
        mListView = (ListView) rootView.findViewById(android.R.id.list);
        mListView.setHeaderDividersEnabled(false);
        mListView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        mListView.setEmptyView(null);
        if (savedState != null && savedState.containsKey(BUNDLE_LIST_STATE)) {
            mListView.onRestoreInstanceState(savedState.getParcelable(BUNDLE_LIST_STATE));
        }
        mEmptyView = rootView.findViewById(R.id.empty_view);
        if (savedState != null && savedState.containsKey(BUNDLE_SELECTED_FOLDER)) {
            mSelectedFolderUri = Uri.parse(savedState.getString(BUNDLE_SELECTED_FOLDER));
            mSelectedFolderType = savedState.getInt(BUNDLE_SELECTED_TYPE);
        } else if (mParentFolder != null) {
            mSelectedFolderUri = mParentFolder.uri;
            // No selected folder type required for hierarchical lists.
        }

        return rootView;
    }

    @Override
    public void onStart() {
        super.onStart();
    }

    @Override
    public void onStop() {
        super.onStop();
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mListView != null) {
            outState.putParcelable(BUNDLE_LIST_STATE, mListView.onSaveInstanceState());
        }
        if (mSelectedFolderUri != null) {
            outState.putString(BUNDLE_SELECTED_FOLDER, mSelectedFolderUri.toString());
        }
        outState.putInt(BUNDLE_SELECTED_TYPE, mSelectedFolderType);
    }

    @Override
    public void onDestroyView() {
        if (mCursorAdapter != null) {
            mCursorAdapter.destroy();
        }
        // Clear the adapter.
        setListAdapter(null);
        if (mFolderObserver != null) {
            FolderController controller = mActivity.getFolderController();
            if (controller != null) {
                controller.unregisterFolderObserver(mFolderObserver);
                mFolderObserver = null;
            }
        }
        super.onDestroyView();
    }

    @Override
    public void onListItemClick(ListView l, View v, int position, long id) {
        viewFolder(position);
    }

    /**
     * Display the conversation list from the folder at the position given.
     * @param position
     */
    private void viewFolder(int position) {
        final Object item = getListAdapter().getItem(position);
        final Folder folder;
        if (item instanceof FolderListAdapter.Item) {
            final FolderListAdapter.Item folderItem = (FolderListAdapter.Item) item;
            folder = mCursorAdapter.getFullFolder(folderItem);
            mSelectedFolderType = folderItem.mFolderType;
        } else if (item instanceof Folder) {
            folder = (Folder) item;
        } else {
            folder = new Folder((Cursor) item);
        }
        if (folder != null) {
            // Since we may be looking at hierarchical views, if we can
            // determine the parent of the folder we have tapped, set it here.
            // If we are looking at the folder we are already viewing, don't
            // update its parent!
            folder.parent = folder.equals(mParentFolder) ? null : mParentFolder;
            // Go to the conversation list for this folder.
            mListener.onFolderSelected(folder);
        } else {
            LogUtils.d(LOG_TAG, "FolderListFragment unable to get a full fledged folder" +
                    " to hand to the listener for position %d", position);
        }
    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        mListView.setEmptyView(null);
        mEmptyView.setVisibility(View.GONE);
        return new CursorLoader(mActivity.getActivityContext(), mFolderListUri,
                UIProvider.FOLDERS_PROJECTION, null, null, null);
    }

    public void onAnimationEnd() {
        if (mFutureData != null) {
            updateCursorAdapter(mFutureData);
            mFutureData = null;
        }
    }

    private void updateCursorAdapter(Cursor data) {
        mCursorAdapter.setCursor(data);
        if (data == null || data.getCount() == 0) {
            mEmptyView.setVisibility(View.VISIBLE);
            mListView.setEmptyView(mEmptyView);
        }
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        if (!mConversationListCallback.isAnimating()) {
            updateCursorAdapter(data);
        } else {
            mFutureData = data;
        }
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        mCursorAdapter.setCursor(null);
    }

    /**
     * Interface for all cursor adapters that allow setting a cursor and being destroyed.
     */
    private interface FolderListFragmentCursorAdapter extends ListAdapter {
        /** Update the folder list cursor with the cursor given here. */
        void setCursor(Cursor cursor);
        /** Get the cursor associated with this adapter **/
        Folder getFullFolder(FolderListAdapter.Item item);
        /** Remove all observers and destroy the object. */
        void destroy();
        /** Notifies the adapter that the data has changed. */
        void notifyDataSetChanged();
    }

    /**
     * An adapter for flat folder lists.
     */
    private class FolderListAdapter extends BaseAdapter implements FolderListFragmentCursorAdapter {

        private final RecentFolderObserver mRecentFolderObserver = new RecentFolderObserver() {
            @Override
            public void onChanged() {
                recalculateList();
            }
        };

        private final RecentFolderList mRecentFolders;
        /** True if the list is sectioned, false otherwise */
        private final boolean mIsSectioned;
        private final LayoutInflater mInflater;
        /** All the items */
        private final List<Item> mItemList = new ArrayList<Item>();
        /** Cursor into the folder list. This might be null. */
        private Cursor mCursor = null;

        /** A union of either a folder or a resource string */
        private class Item {
            public int mPosition;
            public final Folder mFolder;
            public final int mResource;
            /** Either {@link #VIEW_FOLDER} or {@link #VIEW_HEADER} */
            public final int mType;
            /** A normal folder, also a child, if a parent is specified. */
            private static final int VIEW_FOLDER = 0;
            /** A text-label which serves as a header in sectioned lists. */
            private static final int VIEW_HEADER = 1;

            /**
             * Either {@link #FOLDER_SYSTEM}, {@link #FOLDER_RECENT} or {@link #FOLDER_USER} when
             * {@link #mType} is {@link #VIEW_FOLDER}, and {@link #NOT_A_FOLDER} otherwise.
             */
            public final int mFolderType;
            private static final int NOT_A_FOLDER = 0;
            private static final int FOLDER_SYSTEM = 1;
            private static final int FOLDER_RECENT = 2;
            private static final int FOLDER_USER = 3;

            /**
             * Create a folder item with the given type.
             * @param folder
             * @param folderType one of {@link #FOLDER_SYSTEM}, {@link #FOLDER_RECENT} or
             * {@link #FOLDER_USER}
             */
            private Item(Folder folder, int folderType, int cursorPosition) {
                mFolder = folder;
                mResource = -1;
                mType = VIEW_FOLDER;
                mFolderType = folderType;
                mPosition = cursorPosition;
            }
            /**
             * Create a header item with a string resource.
             * @param resource the string resource: R.string.all_folders_heading
             */
            private Item(int resource) {
                mFolder = null;
                mResource = resource;
                mType = VIEW_HEADER;
                mFolderType = NOT_A_FOLDER;
            }

            private final View getView(int position, View convertView, ViewGroup parent) {
                if (mType == VIEW_FOLDER) {
                    return getFolderView(position, convertView, parent);
                } else {
                    return getHeaderView(position, convertView, parent);
                }
            }

            /**
             * Returns a text divider between sections.
             * @param convertView
             * @param parent
             * @return a text header at the given position.
             */
            private final View getHeaderView(int position, View convertView, ViewGroup parent) {
                final TextView headerView;
                if (convertView != null) {
                    headerView = (TextView) convertView;
                } else {
                    headerView = (TextView) mInflater.inflate(
                            R.layout.folder_list_header, parent, false);
                }
                headerView.setText(mResource);
                return headerView;
            }

            /**
             * Return a folder: either a parent folder or a normal (child or flat)
             * folder.
             * @param position
             * @param convertView
             * @param parent
             * @return a view showing a folder at the given position.
             */
            private final View getFolderView(int position, View convertView, ViewGroup parent) {
                final FolderItemView folderItemView;
                if (convertView != null) {
                    folderItemView = (FolderItemView) convertView;
                } else {
                    folderItemView =
                            (FolderItemView) mInflater.inflate(R.layout.folder_item, null, false);
                }
                folderItemView.bind(mFolder, mActivity);
                if (mListView != null) {
                    final boolean isSelected = (mFolderType == mSelectedFolderType)
                            && mFolder.uri.equals(mSelectedFolderUri);
                    mListView.setItemChecked(position, isSelected);
                }
                Folder.setFolderBlockColor(mFolder, folderItemView.findViewById(R.id.color_block));
                Folder.setIcon(mFolder, (ImageView) folderItemView.findViewById(R.id.folder_box));
                return folderItemView;
            }
        }

        /**
         * Creates a {@link FolderListAdapter}.This is a flat folder list of all the folders for the
         * given account.
         * @param layout
         * @param isSectioned TODO(viki):
         */
        public FolderListAdapter(int layout, boolean isSectioned) {
            super();
            mInflater = LayoutInflater.from(mActivity.getActivityContext());
            mIsSectioned = isSectioned;
            final RecentFolderController controller = mActivity.getRecentFolderController();
            if (controller != null && mIsSectioned) {
                mRecentFolders = mRecentFolderObserver.initialize(controller);
            } else {
                mRecentFolders = null;
            }
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            return ((Item) getItem(position)).getView(position, convertView, parent);
        }

        @Override
        public int getViewTypeCount() {
            // Headers and folders
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return ((Item) getItem(position)).mType;
        }

        @Override
        public int getCount() {
            return mItemList.size();
        }

        @Override
        public boolean isEnabled(int position) {
            // We disallow taps on headers
            return ((Item) getItem(position)).mType != Item.VIEW_HEADER;
        }

        @Override
        public boolean areAllItemsEnabled() {
            // The headers are not enabled.
            return false;
        }

        /**
         * Returns all the recent folders from the list given here. Safe to call with a null list.
         * @param recentList
         * @return a valid list of folders, which are all recent folders.
         */
        private final List<Folder> getRecentFolders(RecentFolderList recentList) {
            final List<Folder> folderList = new ArrayList<Folder>();
            if (recentList == null) {
                return folderList;
            }
            // Get all recent folders, after removing system folders.
            for (final Folder f : recentList.getRecentFolderList(null)) {
                if (!f.isProviderFolder()) {
                    folderList.add(f);
                }
            }
            return folderList;
        }

        /**
         * Recalculates the system, recent and user label lists. Notifies that the data has changed.
         * This method modifies all the three lists on every single invocation.
         */
        private void recalculateList() {
            if (mCursor == null || mCursor.isClosed() || mCursor.getCount() <= 0
                    || !mCursor.moveToFirst()) {
                return;
            }
            mItemList.clear();
            if (!mIsSectioned) {
                // Adapter for a flat list. Everything is a FOLDER_USER, and there are no headers.
                do {
                    final Folder f = Folder.getDeficientDisplayOnlyFolder(mCursor);
                    if (mExcludedFolderTypes == null || !mExcludedFolderTypes.contains(f.type)) {
                        mItemList.add(new Item(f, Item.FOLDER_USER, mCursor.getPosition()));
                    }
                } while (mCursor.moveToNext());
                // Ask the list to invalidate its views.
                notifyDataSetChanged();
                return;
            }

            // Otherwise, this is an adapter for a sectioned list.
            // First add all the system folders.
            final List<Item> userFolderList = new ArrayList<Item>();
            do {
                final Folder f = Folder.getDeficientDisplayOnlyFolder(mCursor);
                if (mExcludedFolderTypes == null || !mExcludedFolderTypes.contains(f.type)) {
                    if (f.isProviderFolder()) {
                        mItemList.add(new Item(f, Item.FOLDER_SYSTEM, mCursor.getPosition()));
                    } else {
                        userFolderList.add(new Item(f, Item.FOLDER_USER, mCursor.getPosition()));
                    }
                }
            } while (mCursor.moveToNext());
            // If there are recent folders, add them and a header.
            final List<Folder> recentFolderList = getRecentFolders(mRecentFolders);

            // Remove any excluded folder types
            if (mExcludedFolderTypes != null) {
                final Iterator<Folder> iterator = recentFolderList.iterator();
                while (iterator.hasNext()) {
                    if (mExcludedFolderTypes.contains(iterator.next().type)) {
                        iterator.remove();
                    }
                }
            }

            if (recentFolderList.size() > 0) {
                mItemList.add(new Item(R.string.recent_folders_heading));
                for (Folder f : recentFolderList) {
                    mItemList.add(new Item(f, Item.FOLDER_RECENT, -1));
                }
            }
            // If there are user folders, add them and a header.
            if (userFolderList.size() > 0) {
                mItemList.add(new Item(R.string.all_folders_heading));
                for (final Item i : userFolderList) {
                    mItemList.add(i);
                }
            }
            // Ask the list to invalidate its views.
            notifyDataSetChanged();
        }

        @Override
        public void setCursor(Cursor cursor) {
            mCursor = cursor;
            recalculateList();
        }

        @Override
        public Object getItem(int position) {
            return mItemList.get(position);
        }

        @Override
        public long getItemId(int position) {
            return getItem(position).hashCode();
        }

        @Override
        public final void destroy() {
            mRecentFolderObserver.unregisterAndDestroy();
        }

        @Override
        public Folder getFullFolder(Item folderItem) {
            if (folderItem.mFolderType == Item.FOLDER_RECENT) {
                return folderItem.mFolder;
            } else {
                int pos = folderItem.mPosition;
                if (mFutureData != null) {
                    mCursor = mFutureData;
                    mFutureData = null;
                }
                if (pos > -1 && mCursor != null && !mCursor.isClosed()
                        && mCursor.moveToPosition(folderItem.mPosition)) {
                    return new Folder(mCursor);
                } else {
                    return null;
                }
            }
        }
    }

    private class HierarchicalFolderListAdapter extends ArrayAdapter<Folder>
            implements FolderListFragmentCursorAdapter{

        private static final int PARENT = 0;
        private static final int CHILD = 1;
        private final Uri mParentUri;
        private final Folder mParent;
        private final FolderItemView.DropHandler mDropHandler;
        private Cursor mCursor;

        public HierarchicalFolderListAdapter(Cursor c, Folder parentFolder) {
            super(mActivity.getActivityContext(), R.layout.folder_item);
            mDropHandler = mActivity;
            mParent = parentFolder;
            mParentUri = parentFolder.uri;
            setCursor(c);
        }

        @Override
        public int getViewTypeCount() {
            // Child and Parent
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            Folder f = getItem(position);
            return f.uri.equals(mParentUri) ? PARENT : CHILD;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            FolderItemView folderItemView;
            Folder folder = getItem(position);
            boolean isParent = folder.uri.equals(mParentUri);
            if (convertView != null) {
                folderItemView = (FolderItemView) convertView;
            } else {
                int resId = isParent ? R.layout.folder_item : R.layout.child_folder_item;
                folderItemView = (FolderItemView) LayoutInflater.from(
                        mActivity.getActivityContext()).inflate(resId, null);
            }
            folderItemView.bind(folder, mDropHandler);
            if (folder.uri.equals(mSelectedFolderUri)) {
                getListView().setItemChecked(position, true);
            }
            Folder.setFolderBlockColor(folder, folderItemView.findViewById(R.id.folder_box));
            return folderItemView;
        }

        @Override
        public void setCursor(Cursor cursor) {
            mCursor = cursor;
            clear();
            if (mParent != null) {
                add(mParent);
            }
            if (cursor != null && cursor.getCount() > 0) {
                cursor.moveToFirst();
                do {
                    Folder f = new Folder(cursor);
                    f.parent = mParent;
                    add(f);
                } while (cursor.moveToNext());
            }
        }

        @Override
        public void destroy() {
            // Do nothing.
        }

        @Override
        public Folder getFullFolder(FolderListAdapter.Item folderItem) {
            int pos = folderItem.mPosition;
            if (mCursor == null || mCursor.isClosed()) {
                // See if we have a cursor hanging out we can use
                mCursor = mFutureData;
                mFutureData = null;
            }
            if (pos > -1 && mCursor != null && !mCursor.isClosed()
                    && mCursor.moveToPosition(folderItem.mPosition)) {
                return new Folder(mCursor);
            } else {
                return null;
            }
        }
    }

    /**
     * Sets the currently selected folder safely.
     * @param folder
     */
    private void setSelectedFolder(Folder folder) {
        if (folder == null) {
            mSelectedFolderUri = Uri.EMPTY;
            return;
        }
        mSelectedFolderUri = folder.uri;
        setSelectedFolderType(folder);
        if (mCursorAdapter != null) {
            mCursorAdapter.notifyDataSetChanged();
        }
    }

    /**
     * Sets the selected folder type safely.
     * @param folder
     */
    private void setSelectedFolderType(Folder folder) {
        // If it is set already, assume it is correct.
        if (mSelectedFolderType != FolderListAdapter.Item.NOT_A_FOLDER) {
            return;
        }
        mSelectedFolderType = folder.isProviderFolder() ? FolderListAdapter.Item.FOLDER_SYSTEM
                : FolderListAdapter.Item.FOLDER_USER;
    }

    public interface FolderListSelectionListener {
        public void onFolderSelected(Folder folder);
    }

    /**
     * Get whether the FolderListFragment is currently showing the hierarchy
     * under a single parent.
     */
    public boolean showingHierarchy() {
        return mParentFolder != null;
    }
}
