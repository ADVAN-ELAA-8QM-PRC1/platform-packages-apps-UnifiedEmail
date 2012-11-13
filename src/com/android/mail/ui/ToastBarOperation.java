/*******************************************************************************
 *      Copyright (C) 2011 Google Inc.
 *      Licensed to The Android Open Source Project.
 *
 *      Licensed under the Apache License, Version 2.0 (the "License");
 *      you may not use this file except in compliance with the License.
 *      You may obtain a copy of the License at
 *
 *           http://www.apache.org/licenses/LICENSE-2.0
 *
 *      Unless required by applicable law or agreed to in writing, software
 *      distributed under the License is distributed on an "AS IS" BASIS,
 *      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *      See the License for the specific language governing permissions and
 *      limitations under the License.
 *******************************************************************************/
package com.android.mail.ui;

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.mail.R;
import com.android.mail.providers.Folder;

/**
 * A simple holder class that stores the information to undo the application of a folder.
 */
public class ToastBarOperation implements Parcelable {
    public static final int UNDO = 0;
    public static final int ERROR = 1;
    private final int mAction;
    private final int mCount;
    private final boolean mBatch;
    private final int mType;

    /**
     * Create a ToastBarOperation
     *
     * @param count Number of conversations this action would be applied to.
     * @param menuId res id identifying the menu item tapped; used to determine
     *            what action was performed
     */
    public ToastBarOperation(int count, int menuId, int type, boolean batch) {
        mCount = count;
        mAction = menuId;
        mBatch = batch;
        mType = type;
    }

    public int getType() {
        return mType;
    }

    public boolean isBatchUndo() {
        return mBatch;
    }

    public ToastBarOperation(Parcel in) {
        mCount = in.readInt();
        mAction = in.readInt();
        mBatch = in.readInt() != 0;
        mType = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mCount);
        dest.writeInt(mAction);
        dest.writeInt(mBatch ? 1 : 0);
        dest.writeInt(mType);
    }

    public static final Creator<ToastBarOperation> CREATOR = new Creator<ToastBarOperation>() {
        @Override
        public ToastBarOperation createFromParcel(Parcel source) {
            return new ToastBarOperation(source);
        }

        @Override
        public ToastBarOperation[] newArray(int size) {
            return new ToastBarOperation[size];
        }
    };

    /**
     * Get a string description of the operation that will be performed
     * when the user taps the undo bar.
     */
    public String getDescription(Context context, Folder folder) {
        int resId = -1;
        switch (mAction) {
            case R.id.delete:
                resId = R.plurals.conversation_deleted;
                break;
            case R.id.remove_folder:
                return context.getString(R.string.folder_removed, folder.name);
            case R.id.change_folder:
                resId = R.plurals.conversation_folder_changed;
                break;
            case R.id.archive:
                resId = R.plurals.conversation_archived;
                break;
            case R.id.report_spam:
                resId = R.plurals.conversation_spammed;
                break;
            case R.id.mark_not_spam:
                resId = R.plurals.conversation_not_spam;
                break;
            case R.id.mark_not_important:
                resId = R.plurals.conversation_not_important;
                break;
            case R.id.mute:
                resId = R.plurals.conversation_muted;
                break;
            case R.id.remove_star:
                resId = R.plurals.conversation_unstarred;
                break;
            case R.id.report_phishing:
                resId = R.plurals.conversation_phished;
                break;
            default:
                resId = -1;
                break;
        }
        final String desc = (resId == -1) ? "" :
                String.format(context.getResources().getQuantityString(resId, mCount), mCount);
        return desc;
    }

    public String getSingularDescription(Context context, Folder folder) {
        if (mAction == R.id.remove_folder) {
            return context.getString(R.string.folder_removed, folder.name);
        }
        int resId = -1;
        switch (mAction) {
            case R.id.delete:
                resId = R.string.deleted;
                break;
            case R.id.archive:
                resId = R.string.archived;
                break;
        }
        return (resId == -1) ? "" : context.getString(resId);
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
