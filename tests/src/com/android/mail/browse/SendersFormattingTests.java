/*******************************************************************************
 *      Copyright (C) 2012 Google Inc.
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

package com.android.mail.browse;

import android.test.AndroidTestCase;
import android.text.SpannableString;

import com.android.mail.providers.ConversationInfo;
import com.android.mail.providers.MessageInfo;
import com.google.android.common.html.parser.HtmlParser;
import com.google.android.common.html.parser.HtmlTreeBuilder;

public class SendersFormattingTests extends AndroidTestCase {

    private static ConversationInfo createConversationInfo(int count) {
        int draftCount = 5;
        String first = "snippet", firstUnread = first, last = first;
        return new ConversationInfo(count, draftCount, first, firstUnread, last);
    }

    public void testMe() {
        // Blank sender == from "me"
        ConversationInfo conv = createConversationInfo(1);
        boolean read = false, starred = false;
        MessageInfo info = new MessageInfo(read, starred, null, -1);
        conv.addMessage(info);
        SpannableString[] strings = SendersView.format(getContext(), conv, "", 100,
                new HtmlParser(), new HtmlTreeBuilder());
        assertEquals(strings.length, 1);
        assertEquals(strings[0].toString(), "me");

        ConversationInfo conv2 = createConversationInfo(1);
        MessageInfo info2 = new MessageInfo(read, starred, "", -1);
        conv2.addMessage(info2);
        strings = SendersView.format(getContext(), conv, "", 100, new HtmlParser(),
                new HtmlTreeBuilder());
        assertEquals(strings.length, 1);
        assertEquals(strings[0].toString(), "me");

        ConversationInfo conv3 = createConversationInfo(2);
        MessageInfo info3 = new MessageInfo(read, starred, "", -1);
        conv3.addMessage(info3);
        MessageInfo info4 = new MessageInfo(read, starred, "", -1);
        conv3.addMessage(info4);
        strings = SendersView.format(getContext(), conv, "", 100, new HtmlParser(),
                new HtmlTreeBuilder());
        assertEquals(strings.length, 1);
        assertEquals(strings[0].toString(), "me");
    }

    public void testDupes() {
        // Duplicate sender; should only return 1
        ConversationInfo conv = createConversationInfo(2);
        boolean read = false, starred = false;
        String sender = "sender@sender.com";
        MessageInfo info = new MessageInfo(read, starred, sender, -1);
        conv.addMessage(info);
        MessageInfo info2 = new MessageInfo(read, starred, sender, -1);
        conv.addMessage(info2);
        SpannableString[] strings = SendersView.format(getContext(), conv, "", 100,
                new HtmlParser(), new HtmlTreeBuilder());
        assertEquals(strings.length, 1);
        assertEquals(strings[0].toString(), sender);
    }
}
