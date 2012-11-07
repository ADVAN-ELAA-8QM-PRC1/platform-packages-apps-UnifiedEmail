/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.mail.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.RemoteViews;

import com.android.mail.R;
import com.android.mail.persistence.Persistence;
import com.android.mail.providers.Account;
import com.android.mail.providers.Folder;
import com.android.mail.providers.UIProvider;
import com.android.mail.ui.MailboxSelectionActivity;
import com.android.mail.utils.AccountUtils;
import com.android.mail.utils.LogTag;
import com.android.mail.utils.LogUtils;
import com.android.mail.utils.Utils;
import com.google.common.collect.Sets;
import com.google.common.primitives.Ints;

import java.util.Set;

public abstract class BaseWidgetProvider extends AppWidgetProvider {
    public static final String EXTRA_ACCOUNT = "account";
    public static final String EXTRA_FOLDER = "folder";
    public static final String EXTRA_UNREAD = "unread";
    public static final String EXTRA_UPDATE_ALL_WIDGETS = "update-all-widgets";
    public static final String WIDGET_ACCOUNT_PREFIX = "widget-account-";

    static final String ACCOUNT_FOLDER_PREFERENCE_SEPARATOR = " ";


    protected static final String ACTION_UPDATE_WIDGET = "com.android.mail.ACTION_UPDATE_WIDGET";
    protected static final String EXTRA_WIDGET_ID = "widgetId";

    private static final String LOG_TAG = LogTag.getLogTag();

    /**
     * Remove preferences when deleting widget
     */
    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        super.onDeleted(context, appWidgetIds);

        // TODO: (mindyp) save widget information.
        Editor editor = Persistence.getInstance().getPreferences(context).edit();
        for (int i = 0; i < appWidgetIds.length; ++i) {
            // Remove the account in the preference
            editor.remove(WIDGET_ACCOUNT_PREFIX + appWidgetIds[i]);
        }
        editor.apply();

    }

    /**
     * If a widget provider extends this class, this method needs to be overriden, so the correct
     * widget ids are returned.
     * @return the list ids for the currently configured widgets.
     */
    protected int[] getCurrentWidgetIds(Context context) {
        final AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
        final ComponentName mailComponent =
                new ComponentName(context, WidgetProvider.PROVIDER_NAME);
        return appWidgetManager.getAppWidgetIds(mailComponent);
    }

    /**
     * Get an array of account/mailbox string pairs for currently configured widgets
     * @return the account/mailbox string pairs
     */
    static public String[][] getWidgetInfo(Context context, int[] widgetIds) {
        final String[][] widgetInfo = new String[widgetIds.length][2];
        for (int i = 0; i < widgetIds.length; i++) {
            // Retrieve the persisted information for this widget from
            // preferences.
            final String accountFolder = Persistence.getInstance()
                    .getPreferences(context).getString(WIDGET_ACCOUNT_PREFIX + widgetIds[i], null);
            // If the account matched, update the widget.
            if (accountFolder != null) {
                widgetInfo[i] = TextUtils.split(accountFolder, ACCOUNT_FOLDER_PREFERENCE_SEPARATOR);
            }
        }
        return widgetInfo;
    }

    /**
     * Catches ACTION_NOTIFY_DATASET_CHANGED intent and update the corresponding
     * widgets.
     */
    @Override
    public void onReceive(Context context, Intent intent) {
        // We want to migrate any legacy Email widget information to the new format
        migrateAllLegacyWidgetInformation(context);

        super.onReceive(context, intent);
        LogUtils.d(LOG_TAG, "BaseWidgetProvider.onReceive: %s", intent);

        final String action = intent.getAction();
        if (ACTION_UPDATE_WIDGET.equals(action)) {
            final int widgetId = intent.getIntExtra(EXTRA_WIDGET_ID, -1);
            final Account account = Account.newinstance(intent.getStringExtra(EXTRA_ACCOUNT));
            Folder folder = Folder.fromString(intent.getStringExtra(EXTRA_FOLDER));
            if (widgetId != -1 && account != null && folder != null) {
                updateWidgetInternal(context, widgetId, account, folder);
            }
        } else if (Utils.ACTION_NOTIFY_DATASET_CHANGED.equals(action)) {
            // Receive notification for a certain account.
            final Bundle extras = intent.getExtras();
            final Uri accountUri = (Uri)extras.getParcelable(Utils.EXTRA_ACCOUNT_URI);
            final Uri folderUri = (Uri)extras.getParcelable(Utils.EXTRA_FOLDER_URI);
            final boolean updateAllWidgets = extras.getBoolean(EXTRA_UPDATE_ALL_WIDGETS, false);

            if (accountUri == null && folderUri == null && !updateAllWidgets) {
                return;
            }
            final Set<Integer> widgetsToUpdate = Sets.newHashSet();
            for (int id : getCurrentWidgetIds(context)) {
                // Retrieve the persisted information for this widget from
                // preferences.
                final String accountFolder = Persistence.getInstance()
                        .getPreferences(context).getString(WIDGET_ACCOUNT_PREFIX + id, null);
                // If the account matched, update the widget.
                if (accountFolder != null) {
                    final String[] parsedInfo = TextUtils.split(accountFolder,
                            ACCOUNT_FOLDER_PREFERENCE_SEPARATOR);
                    boolean updateThis = updateAllWidgets;
                    if (!updateThis) {
                        if (accountUri != null &&
                                TextUtils.equals(accountUri.toString(), parsedInfo[0])) {
                            updateThis = true;
                        } else if (folderUri != null &&
                                TextUtils.equals(folderUri.toString(), parsedInfo[1])) {
                            updateThis = true;
                        }
                    }
                    if (updateThis) {
                        widgetsToUpdate.add(id);
                    }
                }
            }
            if (widgetsToUpdate.size() > 0) {
                final int[] widgets = Ints.toArray(widgetsToUpdate);
                AppWidgetManager.getInstance(context).notifyAppWidgetViewDataChanged(widgets,
                        R.id.conversation_list);
            }
        }
    }

    /**
     * Update all widgets in the list
     */
    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        migrateLegacyWidgets(context, appWidgetIds);

        super.onUpdate(context, appWidgetManager, appWidgetIds);
        // Update each of the widgets with a remote adapter
        ContentResolver resolver = context.getContentResolver();
        for (int i = 0; i < appWidgetIds.length; ++i) {
            // Get the account for this widget from preference
            final String accountFolder = Persistence.getInstance().getPreferences(context)
                    .getString(WIDGET_ACCOUNT_PREFIX + appWidgetIds[i], null);
            String accountUri = null;
            Uri folderUri = null;
            if (!TextUtils.isEmpty(accountFolder)) {
                final String[] parsedInfo = TextUtils.split(accountFolder,
                        ACCOUNT_FOLDER_PREFERENCE_SEPARATOR);
                if (parsedInfo.length == 2) {
                    accountUri = parsedInfo[0];
                    folderUri = Uri.parse(parsedInfo[1]);
                } else {
                    accountUri = accountFolder;
                    folderUri =  Uri.EMPTY;
                }
            }
            // account will be null the first time a widget is created. This is
            // OK, as isAccountValid will return false, allowing the widget to
            // be configured.

            // Lookup the account by URI.
            Account account = null;
            if (!TextUtils.isEmpty(accountUri)) {
                account = getAccountObject(context, accountUri);
            }
            if (Utils.isEmpty(folderUri) && account != null) {
                folderUri = account.settings.defaultInbox;
            }
            Folder folder = null;
            if (!Utils.isEmpty(folderUri)) {
                Cursor folderCursor = null;
                try {
                    folderCursor = resolver.query(folderUri,
                            UIProvider.FOLDERS_PROJECTION, null, null, null);
                    if (folderCursor != null) {
                        if (folderCursor.moveToFirst()) {
                            folder = new Folder(folderCursor);
                        }
                    }
                } finally {
                    if (folderCursor != null) {
                        folderCursor.close();
                    }
                }
            }
            updateWidgetInternal(context, appWidgetIds[i], account, folder);
        }
    }

    protected Account getAccountObject(Context context, String accountUri) {
        final ContentResolver resolver = context.getContentResolver();
        Account account = null;
        Cursor accountCursor = null;
        try {
            accountCursor = resolver.query(Uri.parse(accountUri),
                    UIProvider.ACCOUNTS_PROJECTION, null, null, null);
            if (accountCursor != null) {
                if (accountCursor.moveToFirst()) {
                    account = new Account(accountCursor);
                }
            }
        } finally {
            if (accountCursor != null) {
                accountCursor.close();
            }
        }
        return account;
    }

    /**
     * Update the widget appWidgetId with the given account and folder
     */
    public static void updateWidget(Context context, int appWidgetId, Account account,
                Folder folder) {
        if (account == null || folder == null) {
            LogUtils.e(LOG_TAG,
                    "Missing account or folder.  account: %s folder %s", account, folder);
            return;
        }
        final Intent updateWidgetIntent = new Intent(ACTION_UPDATE_WIDGET);

        updateWidgetIntent.setType(account.mimeType);
        updateWidgetIntent.putExtra(EXTRA_WIDGET_ID, appWidgetId);
        updateWidgetIntent.putExtra(EXTRA_ACCOUNT, account.serialize());
        updateWidgetIntent.putExtra(EXTRA_FOLDER, Folder.toString(folder));

        context.sendBroadcast(updateWidgetIntent);
    }

    protected void updateWidgetInternal(Context context, int appWidgetId, Account account,
                Folder folder) {
        RemoteViews remoteViews = new RemoteViews(context.getPackageName(), R.layout.widget);
        final boolean isAccountValid = isAccountValid(context, account);

        if (!isAccountValid || folder == null) {
            // Widget has not been configured yet
            remoteViews.setViewVisibility(R.id.widget_folder, View.GONE);
            remoteViews.setViewVisibility(R.id.widget_account, View.GONE);
            remoteViews.setViewVisibility(R.id.widget_unread_count, View.GONE);
            remoteViews.setViewVisibility(R.id.widget_compose, View.GONE);
            remoteViews.setViewVisibility(R.id.conversation_list, View.GONE);
            remoteViews.setViewVisibility(R.id.widget_folder_not_synced, View.GONE);
            remoteViews.setViewVisibility(R.id.widget_configuration, View.VISIBLE);

            remoteViews.setTextViewText(R.id.empty_conversation_list,
                    context.getString(R.string.loading_conversations));

            final Intent configureIntent = new Intent(context, MailboxSelectionActivity.class);
            configureIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
            configureIntent.setData(Uri.parse(configureIntent.toUri(Intent.URI_INTENT_SCHEME)));
            configureIntent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
            PendingIntent clickIntent = PendingIntent.getActivity(context, 0, configureIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            remoteViews.setOnClickPendingIntent(R.id.widget_configuration, clickIntent);
        } else {
            // Set folder to a space here to avoid flicker.
            configureValidAccountWidget(context, remoteViews, appWidgetId, account, folder, " ");

        }
        AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, remoteViews);
    }

    protected boolean isAccountValid(Context context, Account account) {
        if (account != null) {
            Account[] accounts = AccountUtils.getSyncingAccounts(context);
            for (Account existing : accounts) {
                if (account != null && existing != null && account.uri.equals(existing.uri)) {
                    return true;
                }
            }
        }
        return false;
    }

    protected void configureValidAccountWidget(Context context, RemoteViews remoteViews,
                int appWidgetId, Account account, Folder folder, String folderDisplayName) {
        WidgetService.configureValidAccountWidget(context, remoteViews, appWidgetId, account,
                folder, folderDisplayName, WidgetService.class);
    }

    private boolean isWidgetConfigured(Context context, int widgetId) {
        final SharedPreferences pref = Persistence.getInstance().getPreferences(context);
        return pref.getString(WIDGET_ACCOUNT_PREFIX + widgetId, null) != null;
    }

    private final void migrateAllLegacyWidgetInformation(Context context) {
        final int[] currentWidgetIds = getCurrentWidgetIds(context);
        migrateLegacyWidgets(context, currentWidgetIds);
    }

    private final void migrateLegacyWidgets(Context context, int[] widgetIds) {
        for (int widgetId : widgetIds) {
            // We only want to bother to attempt to upgrade a widget if we don't already
            // have information about.
            if (!isWidgetConfigured(context, widgetId)) {
                migrateLegacyWidgetInformation(context, widgetId);
            }
        }
    }

    /**
     * Abstract method allowing extending classes to perform widget migration
     */
    protected abstract void migrateLegacyWidgetInformation(Context context, int widgetId);

}
