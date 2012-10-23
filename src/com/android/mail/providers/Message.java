/**
 * Copyright (c) 2012, Google Inc.
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

package com.android.mail.providers;

import android.content.AsyncQueryHandler;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.provider.BaseColumns;
import android.text.Html;
import android.text.SpannedString;
import android.text.TextUtils;
import android.text.util.Rfc822Token;
import android.text.util.Rfc822Tokenizer;

import com.android.mail.providers.UIProvider.MessageColumns;
import com.android.mail.utils.Utils;
import com.google.common.base.Objects;

import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;


public class Message implements Parcelable {
    /**
     * Regex pattern used to look for any inline images in message bodies, including Gmail-hosted
     * relative-URL images, Gmail emoticons, and any external inline images (although we usually
     * count on the server to detect external images).
     */
    private static Pattern INLINE_IMAGE_PATTERN = Pattern.compile("<img\\s+[^>]*src=",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    /**
     * @see BaseColumns#_ID
     */
    public long id;
    /**
     * @see UIProvider.MessageColumns#SERVER_ID
     */
    public String serverId;
    /**
     * @see UIProvider.MessageColumns#URI
     */
    public Uri uri;
    /**
     * @see UIProvider.MessageColumns#CONVERSATION_ID
     */
    public Uri conversationUri;
    /**
     * @see UIProvider.MessageColumns#SUBJECT
     */
    public String subject;
    /**
     * @see UIProvider.MessageColumns#SNIPPET
     */
    public String snippet;
    /**
     * @see UIProvider.MessageColumns#FROM
     */
    public String from;
    /**
     * @see UIProvider.MessageColumns#TO
     */
    public String to;
    /**
     * @see UIProvider.MessageColumns#CC
     */
    public String cc;
    /**
     * @see UIProvider.MessageColumns#BCC
     */
    public String bcc;
    /**
     * @see UIProvider.MessageColumns#REPLY_TO
     */
    public String replyTo;
    /**
     * @see UIProvider.MessageColumns#DATE_RECEIVED_MS
     */
    public long dateReceivedMs;
    /**
     * @see UIProvider.MessageColumns#BODY_HTML
     */
    public String bodyHtml;
    /**
     * @see UIProvider.MessageColumns#BODY_TEXT
     */
    public String bodyText;
    /**
     * @see UIProvider.MessageColumns#EMBEDS_EXTERNAL_RESOURCES
     */
    public boolean embedsExternalResources;
    /**
     * @see UIProvider.MessageColumns#REF_MESSAGE_ID
     */
    public String refMessageId;
    /**
     * @see UIProvider.MessageColumns#DRAFT_TYPE
     */
    public int draftType;
    /**
     * @see UIProvider.MessageColumns#APPEND_REF_MESSAGE_CONTENT
     */
    public boolean appendRefMessageContent;
    /**
     * @see UIProvider.MessageColumns#HAS_ATTACHMENTS
     */
    public boolean hasAttachments;
    /**
     * @see UIProvider.MessageColumns#ATTACHMENT_LIST_URI
     */
    public Uri attachmentListUri;
    /**
     * @see UIProvider.MessageColumns#MESSAGE_FLAGS
     */
    public long messageFlags;
    /**
     * @see UIProvider.MessageColumns#SAVE_MESSAGE_URI
     */
    @Deprecated
    public String saveUri;
    /**
     * @see UIProvider.MessageColumns#SEND_MESSAGE_URI
     */
    @Deprecated
    public String sendUri;
    /**
     * @see UIProvider.MessageColumns#ALWAYS_SHOW_IMAGES
     */
    public boolean alwaysShowImages;
    /**
     * @see UIProvider.MessageColumns#READ
     */
    public boolean read;
    /**
     * @see UIProvider.MessageColumns#STARRED
     */
    public boolean starred;
    /**
     * @see UIProvider.MessageColumns#QUOTE_START_POS
     */
    public int quotedTextOffset;
    /**
     * @see UIProvider.MessageColumns#ATTACHMENTS
     *<p>
     * N.B. this value is NOT immutable and may change during conversation view render.
     */
    public String attachmentsJson;
    /**
     * @see UIProvider.MessageColumns#MESSAGE_ACCOUNT_URI
     */
    public Uri accountUri;
    /**
     * @see UIProvider.MessageColumns#EVENT_INTENT_URI
     */
    public Uri eventIntentUri;
    /**
     * @see UIProvider.MessageColumns#SPAM_WARNING_STRING
     */
    public String spamWarningString;
    /**
     * @see UIProvider.MessageColumns#SPAM_WARNING_LEVEL
     */
    public int spamWarningLevel;
    /**
     * @see UIProvider.MessageColumns#SPAM_WARNING_LINK_TYPE
     */
    public int spamLinkType;
    /**
     * @see UIProvider.MessageColumns#VIA_DOMAIN
     */
    public String viaDomain;
    /**
     * @see UIProvider.MessageColumns#IS_SENDING
     */
    public boolean isSending;

    private transient String[] mFromAddresses = null;
    private transient String[] mToAddresses = null;
    private transient String[] mCcAddresses = null;
    private transient String[] mBccAddresses = null;
    private transient String[] mReplyToAddresses = null;

    private transient List<Attachment> mAttachments = null;

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o != null && o instanceof Message
                && Objects.equal(uri, ((Message) o).uri));
    }

    @Override
    public int hashCode() {
        return uri == null ? 0 : uri.hashCode();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(id);
        dest.writeString(serverId);
        dest.writeParcelable(uri, 0);
        dest.writeParcelable(conversationUri, 0);
        dest.writeString(subject);
        dest.writeString(snippet);
        dest.writeString(from);
        dest.writeString(to);
        dest.writeString(cc);
        dest.writeString(bcc);
        dest.writeString(replyTo);
        dest.writeLong(dateReceivedMs);
        dest.writeString(bodyHtml);
        dest.writeString(bodyText);
        dest.writeInt(embedsExternalResources ? 1 : 0);
        dest.writeString(refMessageId);
        dest.writeInt(draftType);
        dest.writeInt(appendRefMessageContent ? 1 : 0);
        dest.writeInt(hasAttachments ? 1 : 0);
        dest.writeParcelable(attachmentListUri, 0);
        dest.writeLong(messageFlags);
        dest.writeString(saveUri);
        dest.writeString(sendUri);
        dest.writeInt(alwaysShowImages ? 1 : 0);
        dest.writeInt(quotedTextOffset);
        dest.writeString(attachmentsJson);
        dest.writeParcelable(accountUri, 0);
        dest.writeParcelable(eventIntentUri, 0);
        dest.writeString(spamWarningString);
        dest.writeInt(spamWarningLevel);
        dest.writeInt(spamLinkType);
        dest.writeString(viaDomain);
        dest.writeInt(isSending ? 1 : 0);
    }

    private Message(Parcel in) {
        id = in.readLong();
        serverId = in.readString();
        uri = in.readParcelable(null);
        conversationUri = in.readParcelable(null);
        subject = in.readString();
        snippet = in.readString();
        from = in.readString();
        to = in.readString();
        cc = in.readString();
        bcc = in.readString();
        replyTo = in.readString();
        dateReceivedMs = in.readLong();
        bodyHtml = in.readString();
        bodyText = in.readString();
        embedsExternalResources = in.readInt() != 0;
        refMessageId = in.readString();
        draftType = in.readInt();
        appendRefMessageContent = in.readInt() != 0;
        hasAttachments = in.readInt() != 0;
        attachmentListUri = in.readParcelable(null);
        messageFlags = in.readLong();
        saveUri = in.readString();
        sendUri = in.readString();
        alwaysShowImages = in.readInt() != 0;
        quotedTextOffset = in.readInt();
        attachmentsJson = in.readString();
        accountUri = in.readParcelable(null);
        eventIntentUri = in.readParcelable(null);
        spamWarningString = in.readString();
        spamWarningLevel = in.readInt();
        spamLinkType = in.readInt();
        viaDomain = in.readString();
        isSending = in.readInt() != 0;
    }

    public Message() {

    }

    @Override
    public String toString() {
        return "[message id=" + id + "]";
    }

    public static final Creator<Message> CREATOR = new Creator<Message>() {

        @Override
        public Message createFromParcel(Parcel source) {
            return new Message(source);
        }

        @Override
        public Message[] newArray(int size) {
            return new Message[size];
        }

    };

    public Message(Cursor cursor) {
        if (cursor != null) {
            id = cursor.getLong(UIProvider.MESSAGE_ID_COLUMN);
            serverId = cursor.getString(UIProvider.MESSAGE_SERVER_ID_COLUMN);
            final String messageUriStr = cursor.getString(UIProvider.MESSAGE_URI_COLUMN);
            uri = !TextUtils.isEmpty(messageUriStr) ? Uri.parse(messageUriStr) : null;
            final String convUriStr = cursor.getString(UIProvider.MESSAGE_CONVERSATION_URI_COLUMN);
            conversationUri = !TextUtils.isEmpty(convUriStr) ? Uri.parse(convUriStr) : null;
            subject = cursor.getString(UIProvider.MESSAGE_SUBJECT_COLUMN);
            snippet = cursor.getString(UIProvider.MESSAGE_SNIPPET_COLUMN);
            from = cursor.getString(UIProvider.MESSAGE_FROM_COLUMN);
            to = cursor.getString(UIProvider.MESSAGE_TO_COLUMN);
            cc = cursor.getString(UIProvider.MESSAGE_CC_COLUMN);
            bcc = cursor.getString(UIProvider.MESSAGE_BCC_COLUMN);
            replyTo = cursor.getString(UIProvider.MESSAGE_REPLY_TO_COLUMN);
            dateReceivedMs = cursor.getLong(UIProvider.MESSAGE_DATE_RECEIVED_MS_COLUMN);
            bodyHtml = cursor.getString(UIProvider.MESSAGE_BODY_HTML_COLUMN);
            bodyText = cursor.getString(UIProvider.MESSAGE_BODY_TEXT_COLUMN);
            embedsExternalResources = cursor
                    .getInt(UIProvider.MESSAGE_EMBEDS_EXTERNAL_RESOURCES_COLUMN) != 0;
            refMessageId = cursor.getString(UIProvider.MESSAGE_REF_MESSAGE_ID_COLUMN);
            draftType = cursor.getInt(UIProvider.MESSAGE_DRAFT_TYPE_COLUMN);
            appendRefMessageContent = cursor
                    .getInt(UIProvider.MESSAGE_APPEND_REF_MESSAGE_CONTENT_COLUMN) != 0;
            hasAttachments = cursor.getInt(UIProvider.MESSAGE_HAS_ATTACHMENTS_COLUMN) != 0;
            final String attachmentsUri = cursor
                    .getString(UIProvider.MESSAGE_ATTACHMENT_LIST_URI_COLUMN);
            attachmentListUri = hasAttachments && !TextUtils.isEmpty(attachmentsUri) ? Uri
                    .parse(attachmentsUri) : null;
            messageFlags = cursor.getLong(UIProvider.MESSAGE_FLAGS_COLUMN);
            saveUri = cursor
                    .getString(UIProvider.MESSAGE_SAVE_URI_COLUMN);
            sendUri = cursor
                    .getString(UIProvider.MESSAGE_SEND_URI_COLUMN);
            alwaysShowImages = cursor.getInt(UIProvider.MESSAGE_ALWAYS_SHOW_IMAGES_COLUMN) != 0;
            read = cursor.getInt(UIProvider.MESSAGE_READ_COLUMN) != 0;
            starred = cursor.getInt(UIProvider.MESSAGE_STARRED_COLUMN) != 0;
            quotedTextOffset = cursor.getInt(UIProvider.QUOTED_TEXT_OFFSET_COLUMN);
            attachmentsJson = cursor.getString(UIProvider.MESSAGE_ATTACHMENTS_COLUMN);
            String accountUriString = cursor.getString(UIProvider.MESSAGE_ACCOUNT_URI_COLUMN);
            accountUri = !TextUtils.isEmpty(accountUriString) ? Uri.parse(accountUriString) : null;
            eventIntentUri =
                    Utils.getValidUri(cursor.getString(UIProvider.MESSAGE_EVENT_INTENT_COLUMN));
            spamWarningString =
                    cursor.getString(UIProvider.MESSAGE_SPAM_WARNING_STRING_ID_COLUMN);
            spamWarningLevel = cursor.getInt(UIProvider.MESSAGE_SPAM_WARNING_LEVEL_COLUMN);
            spamLinkType = cursor.getInt(UIProvider.MESSAGE_SPAM_WARNING_LINK_TYPE_COLUMN);
            viaDomain = cursor.getString(UIProvider.MESSAGE_VIA_DOMAIN_COLUMN);
            isSending = cursor.getInt(UIProvider.MESSAGE_IS_SENDING_COLUMN) != 0;
        }
    }

    public boolean isFlaggedReplied() {
        return (messageFlags & UIProvider.MessageFlags.REPLIED) ==
                UIProvider.MessageFlags.REPLIED;
    }

    public boolean isFlaggedForwarded() {
        return (messageFlags & UIProvider.MessageFlags.FORWARDED) ==
                UIProvider.MessageFlags.FORWARDED;
    }

    public boolean isFlaggedCalendarInvite() {
        return (messageFlags & UIProvider.MessageFlags.CALENDAR_INVITE) ==
                UIProvider.MessageFlags.CALENDAR_INVITE;
    }

    public synchronized String[] getFromAddresses() {
        if (mFromAddresses == null) {
            mFromAddresses = tokenizeAddresses(from);
        }
        return mFromAddresses;
    }

    public synchronized String[] getToAddresses() {
        if (mToAddresses == null) {
            mToAddresses = tokenizeAddresses(to);
        }
        return mToAddresses;
    }

    public synchronized String[] getCcAddresses() {
        if (mCcAddresses == null) {
            mCcAddresses = tokenizeAddresses(cc);
        }
        return mCcAddresses;
    }

    public synchronized String[] getBccAddresses() {
        if (mBccAddresses == null) {
            mBccAddresses = tokenizeAddresses(bcc);
        }
        return mBccAddresses;
    }

    public synchronized String[] getReplyToAddresses() {
        if (mReplyToAddresses == null) {
            mReplyToAddresses = tokenizeAddresses(replyTo);
        }
        return mReplyToAddresses;
    }

    public static String[] tokenizeAddresses(String addresses) {
        if (TextUtils.isEmpty(addresses)) {
            return new String[0];
        }
        Rfc822Token[] tokens = Rfc822Tokenizer.tokenize(addresses);
        String[] strings = new String[tokens.length];
        for (int i = 0; i < tokens.length;i++) {
            strings[i] = tokens[i].toString();
        }
        return strings;
    }

    public List<Attachment> getAttachments() {
        if (mAttachments == null) {
            if (attachmentsJson != null) {
                mAttachments = Attachment.fromJSONArray(attachmentsJson);
            } else {
                mAttachments = Collections.emptyList();
            }
        }
        return mAttachments;
    }

    /**
     * Returns whether a "Show Pictures" button should initially appear for this message. If the
     * button is shown, the message must also block all non-local images in the body. Inversely, if
     * the button is not shown, the message must show all images within (or else the user would be
     * stuck with no images and no way to reveal them).
     *
     * @return true if a "Show Pictures" button should appear.
     */
    public boolean shouldShowImagePrompt() {
        return !alwaysShowImages && embedsExternalResources();
    }

    private boolean embedsExternalResources() {
        return embedsExternalResources ||
                (!TextUtils.isEmpty(bodyHtml) && INLINE_IMAGE_PATTERN.matcher(bodyHtml).find());
    }

    /**
     * Helper method to command a provider to mark all messages from this sender with the
     * {@link MessageColumns#ALWAYS_SHOW_IMAGES} flag set.
     *
     * @param handler a caller-provided handler to run the query on
     * @param token (optional) token to identify the command to the handler
     * @param cookie (optional) cookie to pass to the handler
     */
    public void markAlwaysShowImages(AsyncQueryHandler handler, int token, Object cookie) {
        alwaysShowImages = true;

        final ContentValues values = new ContentValues(1);
        values.put(UIProvider.MessageColumns.ALWAYS_SHOW_IMAGES, 1);

        handler.startUpdate(token, cookie, uri, values, null, null);
    }

    public String getBodyAsHtml() {
        String body = "";
        if (!TextUtils.isEmpty(bodyHtml)) {
            body = bodyHtml;
        } else if (!TextUtils.isEmpty(bodyText)) {
            body = Html.toHtml(new SpannedString(bodyText));
        }
        return body;
    }

}
