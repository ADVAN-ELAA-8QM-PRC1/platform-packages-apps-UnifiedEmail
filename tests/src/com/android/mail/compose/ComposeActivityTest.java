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

package com.android.mail.compose;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.SmallTest;
import android.text.Html;
import android.text.TextUtils;
import android.text.util.Rfc822Tokenizer;

import com.android.mail.providers.Account;
import com.android.mail.providers.Attachment;
import com.android.mail.providers.Message;
import com.android.mail.providers.ReplyFromAccount;
import com.android.mail.providers.UIProvider;
import com.android.mail.utils.AccountUtils;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.Date;
import java.util.HashSet;

@SmallTest
public class ComposeActivityTest extends ActivityInstrumentationTestCase2<ComposeActivity> {

    private ComposeActivity mActivity;
    private Account mAccount;

    public ComposeActivityTest() {
        super(ComposeActivity.class);
    }

    @Override
    protected void setUp() throws Exception {
        mActivity = getActivity();
        super.setUp();
    }

    private Message getRefMessage() {
        Cursor foldersCursor = mActivity.getContentResolver().query(mAccount.folderListUri,
                UIProvider.FOLDERS_PROJECTION, null, null, null);
        Uri convUri = null;
        if (foldersCursor != null) {
            foldersCursor.moveToFirst();
            convUri = Uri.parse(foldersCursor
                    .getString(UIProvider.FOLDER_CONVERSATION_LIST_URI_COLUMN));
        }
        foldersCursor.close();
        Cursor convCursor = mActivity.getContentResolver().query(convUri,
                UIProvider.CONVERSATION_PROJECTION, null, null, null);
        Uri messagesUri = null;
        if (convCursor != null) {
            convCursor.moveToFirst();
            messagesUri = Uri.parse(convCursor
                    .getString(UIProvider.CONVERSATION_MESSAGE_LIST_URI_COLUMN));
        }
        convCursor.close();
        Cursor msgCursor = mActivity.getContentResolver().query(messagesUri,
                UIProvider.MESSAGE_PROJECTION, null, null, null);
        if (msgCursor != null) {
            msgCursor.moveToFirst();
        }
        return new Message(msgCursor);
    }

    public void setAccount(String accountName) {
        // Get a mock account.
        Account[] results = AccountUtils.getSyncingAccounts(mActivity);
        for (Account account : results) {
            if (account.name.equals(accountName)) {
                mAccount = account;
                mActivity.setAccount(mAccount);
                break;
            }
        }
    }

    /**
     * Test the cases where: The user's reply-to is one of their custom from's
     * and they are replying all to a message where their custom from was a
     * recipient. TODO: verify web behavior
     */
    public void testRecipientsRefReplyAllCustomFromReplyTo() {
        setAccount("account3@mockuiprovider.com");
        final Message refMessage = getRefMessage();
        final String customFrom = "CUSTOMaccount3@mockuiprovider.com";
        refMessage.setFrom("account3@mockuiprovider.com");
        refMessage.setTo("someotheraccount1@mockuiprovider.com, "
                + "someotheraccount2@mockuiprovider.com, someotheraccount3@mockuiprovider.com, "
                + customFrom);
        refMessage.setReplyTo(customFrom);
        final ComposeActivity activity = mActivity;
        final Account account = mAccount;
        mActivity.mFromSpinner = new FromAddressSpinner(mActivity);
        ReplyFromAccount a = new ReplyFromAccount(mAccount, mAccount.uri, customFrom,
                customFrom, customFrom, true, true);
        JSONArray array = new JSONArray();
        array.put(a.serialize());
        mAccount.accountFromAddresses = array.toString();
        ReplyFromAccount currentAccount = new ReplyFromAccount(mAccount, mAccount.uri,
                mAccount.name, mAccount.name, customFrom, true, false);
        mActivity.mFromSpinner.setCurrentAccount(currentAccount);
        mActivity.mFromSpinner.asyncInitFromSpinner(ComposeActivity.REPLY_ALL,
                currentAccount.account, null);
        mActivity.runOnUiThread(new Runnable() {
            public void run() {
                activity.initReplyRecipients(account.name, refMessage, ComposeActivity.REPLY_ALL);
                String[] to = activity.getToAddresses();
                String[] cc = activity.getCcAddresses();
                String[] bcc = activity.getBccAddresses();
                String toAsString = TextUtils.join(",", to);
                assertEquals(3, to.length);
                assertFalse(toAsString.contains(customFrom));
                assertEquals(0, cc.length);
                assertEquals(0, bcc.length);
            }
        });
    }

    /**
     * Test the cases where: The user sent a message to one of
     * their custom froms and just replied to that message
     */
    public void testRecipientsRefReplyAllOnlyAccount() {
        setAccount("account3@mockuiprovider.com");
        final Message refMessage = getRefMessage();
        refMessage.setFrom("account3@mockuiprovider.com");
        refMessage.setTo("account3@mockuiprovider.com");
        final ComposeActivity activity = mActivity;
        final Account account = mAccount;
        mActivity.mFromSpinner = new FromAddressSpinner(mActivity);
        ReplyFromAccount currentAccount = new ReplyFromAccount(mAccount, mAccount.uri,
                mAccount.name, mAccount.name, mAccount.name, true, false);
        mActivity.mFromSpinner.setCurrentAccount(currentAccount);
        mActivity.mFromSpinner.asyncInitFromSpinner(ComposeActivity.REPLY_ALL,
                currentAccount.account, null);
        mActivity.runOnUiThread(new Runnable() {
            public void run() {
                activity.initReplyRecipients(account.name, refMessage, ComposeActivity.REPLY_ALL);
                String[] to = activity.getToAddresses();
                String[] cc = activity.getCcAddresses();
                String[] bcc = activity.getBccAddresses();
                String toAsString = TextUtils.join(",", to);
                assertEquals(1, to.length);
                assertTrue(toAsString.contains(account.name));
                assertEquals(0, cc.length);
                assertEquals(0, bcc.length);
            }
        });
    }

    /**
     * Test the cases where: The user sent a message to one of
     * their custom froms and just replied to that message
     */
    public void testRecipientsRefReplyAllOnlyCustomFrom() {
        setAccount("account3@mockuiprovider.com");
        final Message refMessage = getRefMessage();
        final String customFrom = "CUSTOMaccount3@mockuiprovider.com";
        refMessage.setFrom("account3@mockuiprovider.com");
        refMessage.setTo(customFrom);
        final ComposeActivity activity = mActivity;
        final Account account = mAccount;
        mActivity.mFromSpinner = new FromAddressSpinner(mActivity);
        ReplyFromAccount a = new ReplyFromAccount(mAccount, mAccount.uri, customFrom,
                customFrom, customFrom, true, true);
        JSONArray array = new JSONArray();
        array.put(a.serialize());
        mAccount.accountFromAddresses = array.toString();
        ReplyFromAccount currentAccount = new ReplyFromAccount(mAccount, mAccount.uri,
                mAccount.name, mAccount.name, customFrom, true, false);
        mActivity.mFromSpinner.setCurrentAccount(currentAccount);
        mActivity.mFromSpinner.asyncInitFromSpinner(ComposeActivity.REPLY_ALL,
                currentAccount.account, null);
        mActivity.runOnUiThread(new Runnable() {
            public void run() {
                activity.initReplyRecipients(account.name, refMessage, ComposeActivity.REPLY_ALL);
                String[] to = activity.getToAddresses();
                String[] cc = activity.getCcAddresses();
                String[] bcc = activity.getBccAddresses();
                String toAsString = TextUtils.join(",", to);
                assertEquals(1, to.length);
                assertTrue(toAsString.contains(customFrom));
                assertEquals(0, cc.length);
                assertEquals(0, bcc.length);
            }
        });
    }

    public void testReply() {
        setAccount("account0@mockuiprovider.com");
        final Message refMessage = getRefMessage();
        final ComposeActivity activity = mActivity;
        final Account account = mAccount;
        final String refMessageFromAccount = refMessage.getFrom();

        mActivity.runOnUiThread(new Runnable() {
            public void run() {
                activity.initReplyRecipients(account.name, refMessage, ComposeActivity.REPLY);
                String[] to = activity.getToAddresses();
                String[] cc = activity.getCcAddresses();
                String[] bcc = activity.getBccAddresses();
                assertTrue(to.length == 1);
                assertEquals(refMessageFromAccount,
                        Rfc822Tokenizer.tokenize(to[0])[0].getAddress());
                assertTrue(cc.length == 0);
                assertTrue(bcc.length == 0);
            }
        });
    }

    public void testReplyWithReplyTo() {
        setAccount("account1@mockuiprovider.com");
        final Message refMessage = getRefMessage();
        refMessage.setReplyTo("replytofromaccount1@mock.com");
        final ComposeActivity activity = mActivity;
        final Account account = mAccount;
        final String refReplyToAccount = refMessage.getReplyTo();

        mActivity.runOnUiThread(new Runnable() {
            public void run() {
                activity.initReplyRecipients(account.name, refMessage, ComposeActivity.REPLY);
                String[] to = activity.getToAddresses();
                String[] cc = activity.getCcAddresses();
                String[] bcc = activity.getBccAddresses();
                assertTrue(to.length == 1);
                assertEquals(refReplyToAccount,
                        Rfc822Tokenizer.tokenize(to[0])[0].getAddress());
                assertTrue(cc.length == 0);
                assertTrue(bcc.length == 0);
            }
        });
    }

    /**
     * Reply to a message you sent yourself to some recipients in the to field.
     */
    public void testReplyToSelf() {
        setAccount("account1@mockuiprovider.com");
        final Message refMessage = getRefMessage();
        final ComposeActivity activity = mActivity;
        final Account account = mAccount;
        refMessage.setFrom("Account Test <account1@mockuiprovider.com>");
        refMessage.setTo("test1@gmail.com");
        mActivity.runOnUiThread(new Runnable() {
            public void run() {
                activity.initReplyRecipients(account.name, refMessage, ComposeActivity.REPLY);
                String[] to = activity.getToAddresses();
                String[] cc = activity.getCcAddresses();
                String[] bcc = activity.getBccAddresses();
                assertTrue(to.length == 1);
                String toAsString = TextUtils.join(",", to);
                assertTrue(toAsString.contains("test1@gmail.com"));
                assertTrue(cc.length == 0);
                assertTrue(bcc.length == 0);
            }
        });
    }

    /**
     * Reply-all to a message you sent.
     */
    public void testReplyAllToSelf() {
        setAccount("account1@mockuiprovider.com");
        final Message refMessage = getRefMessage();
        final ComposeActivity activity = mActivity;
        final Account account = mAccount;
        refMessage.setFrom("Account Test <account1@mockuiprovider.com>");
        refMessage.setTo("test1@gmail.com, test2@gmail.com");
        mActivity.runOnUiThread(new Runnable() {
            public void run() {
                activity.initReplyRecipients(account.name, refMessage, ComposeActivity.REPLY_ALL);
                String[] to = activity.getToAddresses();
                String[] cc = activity.getCcAddresses();
                String[] bcc = activity.getBccAddresses();
                assertEquals(to.length, 2);
                String toAsString = TextUtils.join(",", to);
                assertTrue(toAsString.contains("test1@gmail.com"));
                assertTrue(toAsString.contains("test2@gmail.com"));
                assertTrue(cc.length == 0);
                assertTrue(bcc.length == 0);
            }
        });
    }

    /**
     * Reply-all to a message you sent with some to and some CC recips.
     */
    public void testReplyAllToSelfWithCc() {
        setAccount("account1@mockuiprovider.com");
        final Message refMessage = getRefMessage();
        final ComposeActivity activity = mActivity;
        final Account account = mAccount;
        refMessage.setFrom("Account Test <account1@mockuiprovider.com>");
        refMessage.setTo("test1@gmail.com, test2@gmail.com");
        refMessage.setCc("testcc@gmail.com");
        mActivity.runOnUiThread(new Runnable() {
            public void run() {
                activity.initReplyRecipients(account.name, refMessage, ComposeActivity.REPLY_ALL);
                String[] to = activity.getToAddresses();
                String[] cc = activity.getCcAddresses();
                String[] bcc = activity.getBccAddresses();
                assertEquals(to.length, 2);
                String toAsString = TextUtils.join(",", to);
                assertTrue(toAsString.contains("test1@gmail.com"));
                assertTrue(toAsString.contains("test2@gmail.com"));
                String ccAsString = TextUtils.join(",", cc);
                assertTrue(ccAsString.contains("testcc@gmail.com"));
                assertTrue(cc.length == 1);
                assertTrue(bcc.length == 0);
            }
        });
    }

    public void testReplyAll() {
        setAccount("account0@mockuiprovider.com");
        final Message refMessage = getRefMessage();
        final ComposeActivity activity = mActivity;
        final Account account = mAccount;
        final String[] refMessageTo = TextUtils.split(refMessage.getTo(), ",");
        final String refMessageFromAccount = refMessage.getFrom();

        mActivity.runOnUiThread(new Runnable() {
            public void run() {
                activity.initReplyRecipients(account.name, refMessage, ComposeActivity.REPLY_ALL);
                String[] to = activity.getToAddresses();
                String[] cc = activity.getCcAddresses();
                String[] bcc = activity.getBccAddresses();
                assertTrue(to.length == 1);
                assertEquals(refMessageFromAccount,
                        Rfc822Tokenizer.tokenize(to[0])[0].getAddress());
                assertEquals(cc.length, refMessageTo.length);
                assertTrue(bcc.length == 0);
            }
        });
    }

    public void testReplyAllWithReplyTo() {
        setAccount("account1@mockuiprovider.com");
        final Message refMessage = getRefMessage();
        refMessage.setReplyTo("replytofromaccount1@mock.com");
        final ComposeActivity activity = mActivity;
        final Account account = mAccount;
        final String[] refMessageTo = TextUtils.split(refMessage.getTo(), ",");
        final String refReplyToAccount = refMessage.getReplyTo();

        mActivity.runOnUiThread(new Runnable() {
            public void run() {
                activity.initReplyRecipients(account.name, refMessage, ComposeActivity.REPLY_ALL);
                String[] to = activity.getToAddresses();
                String[] cc = activity.getCcAddresses();
                String[] bcc = activity.getBccAddresses();
                assertTrue(to.length == 1);
                assertEquals(refReplyToAccount, Rfc822Tokenizer.tokenize(to[0])[0].getAddress());
                assertEquals(cc.length, refMessageTo.length);
                assertTrue(bcc.length == 0);
            }
        });
    }

    private Message getRefMessageWithCc(long messageId, boolean hasAttachments) {
        MatrixCursor cursor = new MatrixCursor(UIProvider.MESSAGE_PROJECTION);
        final String messageUri = "content://xxx/message/" + messageId;
        Object[] messageValues = new Object[UIProvider.MESSAGE_PROJECTION.length];
        messageValues[UIProvider.MESSAGE_ID_COLUMN] = Long.valueOf(messageId);
        messageValues[UIProvider.MESSAGE_URI_COLUMN] = messageUri;
        messageValues[UIProvider.MESSAGE_SUBJECT_COLUMN] = "Message subject";
        messageValues[UIProvider.MESSAGE_SNIPPET_COLUMN] = "SNIPPET";
        String html = "<html><body><b><i>This is some html!!!</i></b></body></html>";
        messageValues[UIProvider.MESSAGE_BODY_HTML_COLUMN] = html;
        messageValues[UIProvider.MESSAGE_BODY_TEXT_COLUMN] = Html.fromHtml(html);
        messageValues[UIProvider.MESSAGE_HAS_ATTACHMENTS_COLUMN] = hasAttachments ? 1 : 0;
        messageValues[UIProvider.MESSAGE_DATE_RECEIVED_MS_COLUMN] = new Date().getTime();
        messageValues[UIProvider.MESSAGE_ATTACHMENT_LIST_URI_COLUMN] = messageUri
                + "/getAttachments";
        messageValues[UIProvider.MESSAGE_TO_COLUMN] = "account1@mock.com, account2@mock.com";
        messageValues[UIProvider.MESSAGE_FROM_COLUMN] = "fromaccount1@mock.com";
        messageValues[UIProvider.MESSAGE_CC_COLUMN] = "accountcc1@mock.com, accountcc2@mock.com";
        cursor.addRow(messageValues);
        cursor.moveToFirst();
        return new Message(cursor);
    }

    public void testReplyAllWithCc() {
        setAccount("account1@mockuiprovider.com");
        final Message refMessage = getRefMessageWithCc(0, false);
        final ComposeActivity activity = mActivity;
        final Account account = mAccount;
        final String[] refMessageTo = TextUtils.split(refMessage.getTo(), ",");
        final String[] refMessageCc = TextUtils.split(refMessage.getCc(), ",");
        final String refMessageFromAccount = refMessage.getFrom();

        mActivity.runOnUiThread(new Runnable() {
            public void run() {
                activity.initReplyRecipients(account.name, refMessage, ComposeActivity.REPLY_ALL);
                String[] to = activity.getToAddresses();
                String[] cc = activity.getCcAddresses();
                String[] bcc = activity.getBccAddresses();
                assertTrue(to.length == 1);
                assertEquals(refMessageFromAccount, Rfc822Tokenizer.tokenize(to[0])[0].getAddress());
                assertEquals(cc.length, refMessageTo.length + refMessageCc.length);
                HashSet<String> ccMap = new HashSet<String>();
                for (String recip : cc) {
                    ccMap.add(Rfc822Tokenizer.tokenize(recip.trim())[0].getAddress());
                }
                for (String toRecip : refMessageTo) {
                    assertTrue(ccMap.contains(toRecip.trim()));
                }
                for (String ccRecip : refMessageCc) {
                    assertTrue(ccMap.contains(ccRecip.trim()));
                }
                assertTrue(bcc.length == 0);
            }
        });
    }

    public void testForward() {
        setAccount("account0@mockuiprovider.com");
        final Message refMessage = getRefMessage();
        final ComposeActivity activity = mActivity;
        final Account account = mAccount;

        mActivity.runOnUiThread(new Runnable() {
            public void run() {
                activity.initReplyRecipients(account.name, refMessage, ComposeActivity.FORWARD);
                String[] to = activity.getToAddresses();
                String[] cc = activity.getCcAddresses();
                String[] bcc = activity.getBccAddresses();
                assertEquals(to.length, 0);
                assertEquals(cc.length, 0);
                assertEquals(bcc.length, 0);
            }
        });
    }

    public void testCompose() {
        setAccount("account0@mockuiprovider.com");
        final Message refMessage = getRefMessage();
        final ComposeActivity activity = mActivity;
        final Account account = mAccount;

        mActivity.runOnUiThread(new Runnable() {
            public void run() {
                activity.initReplyRecipients(account.name, refMessage, ComposeActivity.COMPOSE);
                String[] to = activity.getToAddresses();
                String[] cc = activity.getCcAddresses();
                String[] bcc = activity.getBccAddresses();
                assertEquals(to.length, 0);
                assertEquals(cc.length, 0);
                assertEquals(bcc.length, 0);
            }
        });
    }

    /**
     * Test the cases where: The user is replying to a message they sent
     */
    public void testRecipientsRefMessageReplyToSelf() {
        setAccount("account0@mockuiprovider.com");
        final Message refMessage = getRefMessage();
        refMessage.setFrom("account0@mockuiprovider.com");
        refMessage.setTo("someotheraccount@mockuiprovider.com");
        final ComposeActivity activity = mActivity;
        final Account account = mAccount;

        mActivity.runOnUiThread(new Runnable() {
            public void run() {
                activity.initReplyRecipients(account.name, refMessage, ComposeActivity.REPLY);
                String[] to = activity.getToAddresses();
                String[] cc = activity.getCcAddresses();
                String[] bcc = activity.getBccAddresses();
                assertEquals(to.length, 1);
                assertTrue(to[0].contains(refMessage.getTo()));
                assertEquals(cc.length, 0);
                assertEquals(bcc.length, 0);
            }
        });
    }

    /**
     * Test the cases where:
     * The user is replying to a message sent from one of their custom froms
     */
    public void testRecipientsRefMessageReplyToCustomFrom() {
        setAccount("account1@mockuiprovider.com");
        final Message refMessage = getRefMessage();
        refMessage.setFrom("CUSTOMaccount1@mockuiprovider.com");
        refMessage.setTo("someotheraccount@mockuiprovider.com");
        final ComposeActivity activity = mActivity;
        final Account account = mAccount;
        mActivity.mFromSpinner = new FromAddressSpinner(mActivity);
        ReplyFromAccount a = new ReplyFromAccount(mAccount, mAccount.uri, refMessage.getFrom(),
                refMessage.getFrom(), refMessage.getFrom(), true, true);
        JSONArray array = new JSONArray();
        array.put(a.serialize());
        mAccount.accountFromAddresses = array.toString();
        ReplyFromAccount currentAccount = new ReplyFromAccount(mAccount, mAccount.uri,
                mAccount.name, mAccount.name, mAccount.name, true, false);
        mActivity.mFromSpinner.setCurrentAccount(currentAccount);
        mActivity.mFromSpinner.asyncInitFromSpinner(ComposeActivity.REPLY, currentAccount.account,
                null);

        mActivity.runOnUiThread(new Runnable() {
            public void run() {
                activity.initReplyRecipients(account.name, refMessage, ComposeActivity.REPLY);
                String[] to = activity.getToAddresses();
                String[] cc = activity.getCcAddresses();
                String[] bcc = activity.getBccAddresses();
                assertEquals(to.length, 1);
                assertTrue(to[0].contains(refMessage.getTo()));
                assertEquals(cc.length, 0);
                assertEquals(bcc.length, 0);
            }
        });
    }

    /**
     * Test the cases where:
     * The user is replying to a message sent from one of their custom froms
     */
    public void testRecipientsRefMessageReplyAllCustomFrom() {
        setAccount("account1@mockuiprovider.com");
        final Message refMessage = getRefMessage();
        final String customFrom = "CUSTOMaccount1@mockuiprovider.com";
        refMessage.setFrom("senderaccount@mockuiprovider.com");
        refMessage.setTo("someotheraccount@mockuiprovider.com, "
                + "someotheraccount2@mockuiprovider.com, someotheraccount4@mockuiprovider.com, "
                + customFrom);
        final ComposeActivity activity = mActivity;
        final Account account = mAccount;
        mActivity.mFromSpinner = new FromAddressSpinner(mActivity);
        ReplyFromAccount a = new ReplyFromAccount(mAccount, mAccount.uri, customFrom,
                customFrom, customFrom, true, true);
        JSONArray array = new JSONArray();
        array.put(a.serialize());
        mAccount.accountFromAddresses = array.toString();
        ReplyFromAccount currentAccount = new ReplyFromAccount(mAccount, mAccount.uri,
                mAccount.name, mAccount.name, mAccount.name, true, false);
        mActivity.mFromSpinner.setCurrentAccount(currentAccount);
        mActivity.mFromSpinner.asyncInitFromSpinner(ComposeActivity.REPLY_ALL,
                currentAccount.account, null);
        mActivity.runOnUiThread(new Runnable() {
            public void run() {
                activity.initReplyRecipients(account.name, refMessage, ComposeActivity.REPLY_ALL);
                String[] to = activity.getToAddresses();
                String[] cc = activity.getCcAddresses();
                String[] bcc = activity.getBccAddresses();
                String toAsString = TextUtils.join(",", to);
                String ccAsString = TextUtils.join(",", cc);
                String bccAsString = TextUtils.join(",", bcc);
                assertEquals(to.length, 1);
                assertFalse(toAsString.contains(customFrom));
                assertFalse(ccAsString.contains(customFrom));
                assertFalse(bccAsString.contains(customFrom));
            }
        });
    }

    /**
     * Test the cases where:
     * The user is replying to a message sent from one of their custom froms
     */
    public void testRecipientsRefMessageReplyAllCustomFromThisAccount() {
        setAccount("account1@mockuiprovider.com");
        final Message refMessage = getRefMessage();
        final String customFrom = "CUSTOMaccount1@mockuiprovider.com";
        refMessage.setFrom("account1@mockuiprovider.com");
        refMessage.setTo("someotheraccount@mockuiprovider.com, "
                + "someotheraccount2@mockuiprovider.com, someotheraccount4@mockuiprovider.com, "
                + customFrom);
        final ComposeActivity activity = mActivity;
        final Account account = mAccount;
        mActivity.mFromSpinner = new FromAddressSpinner(mActivity);
        ReplyFromAccount a = new ReplyFromAccount(mAccount, mAccount.uri, customFrom,
                customFrom, customFrom, true, true);
        JSONArray array = new JSONArray();
        array.put(a.serialize());
        mAccount.accountFromAddresses = array.toString();
        ReplyFromAccount currentAccount = new ReplyFromAccount(mAccount, mAccount.uri,
                mAccount.name, mAccount.name, mAccount.name, true, false);
        mActivity.mFromSpinner.setCurrentAccount(currentAccount);
        mActivity.mFromSpinner.asyncInitFromSpinner(ComposeActivity.REPLY_ALL,
                currentAccount.account, null);
        mActivity.runOnUiThread(new Runnable() {
            public void run() {
                activity.initReplyRecipients(account.name, refMessage, ComposeActivity.REPLY_ALL);
                String[] to = activity.getToAddresses();
                String[] cc = activity.getCcAddresses();
                String[] bcc = activity.getBccAddresses();
                String toAsString = TextUtils.join(",", to);
                String ccAsString = TextUtils.join(",", cc);
                String bccAsString = TextUtils.join(",", bcc);
                // Should have the same count as the original message.
                assertEquals(to.length, 3);
                assertFalse(toAsString.contains(customFrom));
                assertFalse(ccAsString.contains(customFrom));
                assertFalse(bccAsString.contains(customFrom));
            }
        });
    }

    private String createAttachmentsJson() {
        Attachment attachment1 = new Attachment();
        attachment1.contentUri = Uri.parse("www.google.com");
        attachment1.contentType = "img/jpeg";
        attachment1.name = "attachment1";
        Attachment attachment2 = new Attachment();
        attachment2.contentUri = Uri.parse("www.google.com");
        attachment2.contentType = "img/jpeg";
        attachment2.name = "attachment2";
        JSONArray attachments = new JSONArray();
        try {
            attachments.put(attachment1.toJSON());
            attachments.put(attachment2.toJSON());
        } catch (JSONException e) {
            assertTrue(false);
        }
        return attachments.toString();
    }

    // First test: switch reply to reply all to fwd, 1 to recipient, 1 cc recipient.
    public void testChangeModes0() {
        setAccount("account0@mockuiprovider.com");
        final Message refMessage = getRefMessage();
        refMessage.setFrom("fromaccount@mockuiprovider.com");
        refMessage.setTo("account0@mockuiprovider.com");
        refMessage.setCc("ccaccount@mockuiprovider.com");
        final ComposeActivity activity = mActivity;
        final Account account = mAccount;
        mActivity.runOnUiThread(new Runnable() {
            public void run() {
                activity.mRefMessage = refMessage;
                activity.initReplyRecipients(account.name, refMessage, ComposeActivity.REPLY);
                String[] to = activity.getToAddresses();
                String[] cc = activity.getCcAddresses();
                String[] bcc = activity.getBccAddresses();
                assertEquals(1, to.length);
                assertTrue(to[0].contains(refMessage.getFrom()));
                assertEquals(cc.length, 0);
                assertEquals(bcc.length, 0);
                activity.onNavigationItemSelected(1, ComposeActivity.REPLY_ALL);
                assertEquals(activity.getToAddresses().length, 1);
                assertTrue(activity.getToAddresses()[0].contains(refMessage.getFrom()));
                assertEquals(activity.getCcAddresses().length, 1);
                assertTrue(activity.getCcAddresses()[0].contains(refMessage.getCc()));
                assertEquals(activity.getBccAddresses().length, 0);
                activity.onNavigationItemSelected(2, ComposeActivity.FORWARD);
                assertEquals(activity.getToAddresses().length, 0);
                assertEquals(activity.getCcAddresses().length, 0);
                assertEquals(activity.getBccAddresses().length, 0);
            }
        });
    }

    // Switch reply to reply all to fwd, 2 to recipients, 1 cc recipient.
    public void testChangeModes1() {
        setAccount("account0@mockuiprovider.com");
        final Message refMessage = getRefMessage();
        refMessage.setFrom("fromaccount@mockuiprovider.com");
        refMessage.setTo("account0@mockuiprovider.com, toaccount0@mockuiprovider.com");
        refMessage.setCc("ccaccount@mockuiprovider.com");
        final ComposeActivity activity = mActivity;
        final Account account = mAccount;
        mActivity.runOnUiThread(new Runnable() {
            public void run() {
                activity.mRefMessage = refMessage;
                activity.initReplyRecipients(account.name, refMessage, ComposeActivity.REPLY);
                String[] to = activity.getToAddresses();
                String[] cc = activity.getCcAddresses();
                String[] bcc = activity.getBccAddresses();
                assertEquals(to.length, 1);
                assertTrue(to[0].contains(refMessage.getFrom()));
                assertEquals(cc.length, 0);
                assertEquals(bcc.length, 0);
                activity.onNavigationItemSelected(1, ComposeActivity.REPLY_ALL);
                assertEquals(activity.getToAddresses().length, 1);
                assertTrue(activity.getToAddresses()[0].contains(refMessage.getFrom()));
                assertEquals(activity.getCcAddresses().length, 2);
                assertTrue(activity.getCcAddresses()[0].contains(refMessage.getCc())
                        || activity.getCcAddresses()[1].contains(refMessage.getCc()));
                assertTrue(activity.getCcAddresses()[0].contains("toaccount0@mockuiprovider.com")
                        || activity.getCcAddresses()[1]
                                .contains("toaccount0@mockuiprovider.com"));
                assertEquals(activity.getBccAddresses().length, 0);
                activity.onNavigationItemSelected(2, ComposeActivity.FORWARD);
                assertEquals(activity.getToAddresses().length, 0);
                assertEquals(activity.getCcAddresses().length, 0);
                assertEquals(activity.getBccAddresses().length, 0);
            }
        });
    }

    // Switch reply to reply all to fwd, 2 to recipients, 2 cc recipients.
    public void testChangeModes2() {
        setAccount("account0@mockuiprovider.com");
        final Message refMessage = getRefMessage();
        refMessage.setFrom("fromaccount@mockuiprovider.com");
        refMessage.setTo("account0@mockuiprovider.com, toaccount0@mockuiprovider.com");
        refMessage.setCc("ccaccount@mockuiprovider.com, ccaccount2@mockuiprovider.com");
        final ComposeActivity activity = mActivity;
        final Account account = mAccount;
        mActivity.runOnUiThread(new Runnable() {
            public void run() {
                activity.mRefMessage = refMessage;
                activity.initReplyRecipients(account.name, refMessage, ComposeActivity.REPLY);
                String[] to = activity.getToAddresses();
                String[] cc = activity.getCcAddresses();
                String[] bcc = activity.getBccAddresses();
                assertEquals(to.length, 1);
                assertTrue(to[0].contains(refMessage.getFrom()));
                assertEquals(cc.length, 0);
                assertEquals(bcc.length, 0);
                activity.onNavigationItemSelected(1, ComposeActivity.REPLY_ALL);
                assertEquals(activity.getToAddresses().length, 1);
                assertTrue(activity.getToAddresses()[0].contains(refMessage.getFrom()));
                assertEquals(activity.getCcAddresses().length, 3);
                assertTrue(activity.getCcAddresses()[0].contains("ccaccount@mockuiprovider.com")
                        || activity.getCcAddresses()[1].contains("ccaccount@mockuiprovider.com")
                        || activity.getCcAddresses()[2].contains("ccaccount@mockuiprovider.com"));
                assertTrue(activity.getCcAddresses()[0].contains("ccaccount2@mockuiprovider.com")
                        || activity.getCcAddresses()[1].contains("ccaccount2@mockuiprovider.com")
                        || activity.getCcAddresses()[2].contains("ccaccount2@mockuiprovider.com"));
                assertTrue(activity.getCcAddresses()[0].contains("toaccount0@mockuiprovider.com")
                        || activity.getCcAddresses()[1].contains("toaccount0@mockuiprovider.com")
                        || activity.getCcAddresses()[2].contains("toaccount0@mockuiprovider.com"));
                assertTrue(activity.getCcAddresses()[0].contains("toaccount0@mockuiprovider.com")
                        || activity.getCcAddresses()[1]
                                .contains("toaccount0@mockuiprovider.com")
                        || activity.getCcAddresses()[2]
                                .contains("toaccount0@mockuiprovider.com"));
                assertEquals(activity.getBccAddresses().length, 0);
                activity.onNavigationItemSelected(2, ComposeActivity.FORWARD);
                assertEquals(activity.getToAddresses().length, 0);
                assertEquals(activity.getCcAddresses().length, 0);
                assertEquals(activity.getBccAddresses().length, 0);
            }
        });
    }

    // Switch reply to reply all to fwd, 2 attachments.
    public void testChangeModes3() {
        setAccount("account0@mockuiprovider.com");
        final Message refMessage = getRefMessage();
        refMessage.hasAttachments = true;
        refMessage.attachmentsJson = createAttachmentsJson();
        final ComposeActivity activity = mActivity;
        mActivity.runOnUiThread(new Runnable() {
            public void run() {
                activity.mRefMessage = refMessage;
                activity.initAttachments(refMessage);
                assertEquals(activity.getAttachments().size(), 2);
                activity.onNavigationItemSelected(1, ComposeActivity.REPLY);
                assertEquals(activity.getAttachments().size(), 0);
                activity.onNavigationItemSelected(1, ComposeActivity.REPLY_ALL);
                assertEquals(activity.getAttachments().size(), 0);
                activity.onNavigationItemSelected(2, ComposeActivity.FORWARD);
                assertEquals(activity.getAttachments().size(), 2);
            }
        });
    }
}
