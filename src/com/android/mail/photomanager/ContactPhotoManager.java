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

package com.android.mail.photomanager;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.Contacts.Photo;
import android.provider.ContactsContract.Data;
import android.text.TextUtils;
import android.util.LruCache;

import com.android.mail.ui.ImageCanvas;
import com.android.mail.utils.LogUtils;
import com.google.common.base.Objects;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Asynchronously loads contact photos and maintains a cache of photos.
 */
public class ContactPhotoManager extends PhotoManager {
    public static final String CONTACT_PHOTO_SERVICE = "contactPhotos";

    private static final String[] COLUMNS = new String[] { Photo._ID, Photo.PHOTO };

    /**
     * An LRU cache for photo ids mapped to contact addresses.
     */
    private final LruCache<String, Long> mPhotoIdCache;
    private final LetterTileProvider mLetterTileProvider;

    /** Cache size for {@link #mPhotoIdCache}. Starting with 500 entries. */
    private static final int PHOTO_ID_CACHE_SIZE = 500;

    private ContactPhotoManager(Context context) {
        super(context);
        mPhotoIdCache = new LruCache<String, Long>(PHOTO_ID_CACHE_SIZE);
        mLetterTileProvider = new LetterTileProvider(context);
    }

    @Override
    public DefaultImageProvider getDefaultImageProvider() {
        return mLetterTileProvider;
    }

    @Override
    public long getHash(PhotoIdentifier id, ImageCanvas view) {
        ContactIdentifier contact = (ContactIdentifier) id;
        int hash = 23;
        hash = 31 * hash + view.hashCode();
        hash = 31 * hash + contact.pos;
        hash = 31 * hash + (contact.emailAddress != null ? contact.emailAddress.hashCode() : 0);
        return hash;
    }

    @Override
    public PhotoLoaderThread getLoaderThread(ContentResolver contentResolver) {
        return new ContactPhotoLoaderThread(contentResolver);
    }

    /**
     * Requests the singleton instance of {@link AccountTypeManager} with data
     * bound from the available authenticators. This method can safely be called
     * from the UI thread.
     */
    public static ContactPhotoManager getInstance(Context context) {
        Context applicationContext = context.getApplicationContext();
        ContactPhotoManager service =
                (ContactPhotoManager) applicationContext.getSystemService(CONTACT_PHOTO_SERVICE);
        if (service == null) {
            service = createContactPhotoManager(applicationContext);
            LogUtils.e(TAG, "No contact photo service in context: " + applicationContext);
        }
        return service;
    }

    public static synchronized ContactPhotoManager createContactPhotoManager(Context context) {
        return new ContactPhotoManager(context);
    }

    @Override
    public void clear() {
        super.clear();
        mPhotoIdCache.evictAll();
    }

    /**
     * Store the supplied photo id to contact address mapping so that we don't
     * have to lookup the contact again.
     * @param id Id of the photo matching the contact
     * @param contactAddress Email address of the contact
     */
    private void cachePhotoId(Long id, String contactAddress) {
        mPhotoIdCache.put(contactAddress, id);
    }

    public static class ContactIdentifier implements PhotoIdentifier {
        public final String name;
        public final String emailAddress;
        public final int pos;

        public ContactIdentifier(String name, String emailAddress, int pos) {
            this.name = name;
            this.emailAddress = emailAddress;
            this.pos = pos;
        }

        @Override
        public boolean isValid() {
            return !TextUtils.isEmpty(emailAddress);
        }

        @Override
        public Object getKey() {
            return emailAddress;
        }

        @Override
        public int hashCode() {
            int hash = 17;
            hash = 31 * hash + (emailAddress != null ? emailAddress.hashCode() : 0);
            hash = 31 * hash + (name != null ? name.hashCode() : 0);
            hash = 31 * hash + pos;
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            ContactIdentifier other = (ContactIdentifier) obj;
            return Objects.equal(emailAddress, other.emailAddress)
                    && Objects.equal(name, other.name) && Objects.equal(pos, other.pos);
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder("{");
            sb.append(super.toString());
            sb.append(" name=");
            sb.append(name);
            sb.append(" email=");
            sb.append(emailAddress);
            sb.append(" pos=");
            sb.append(pos);
            sb.append("}");
            return sb.toString();
        }
    }

    public class ContactPhotoLoaderThread extends PhotoLoaderThread {
        private static final int PHOTO_PRELOAD_DELAY = 1000;
        private static final int PRELOAD_BATCH = 25;
        /**
         * Maximum number of photos to preload.  If the cache size is 2Mb and
         * the expected average size of a photo is 4kb, then this number should be 2Mb/4kb = 500.
         */
        private static final int MAX_PHOTOS_TO_PRELOAD = 100;

        private final String[] DATA_COLS = new String[] {
            Email.DATA,                 // 0
            Email.PHOTO_ID              // 1
        };

        private static final int DATA_EMAIL_COLUMN = 0;
        private static final int DATA_PHOTO_ID_COLUMN = 1;

        public ContactPhotoLoaderThread(ContentResolver resolver) {
            super(resolver);
        }

        @Override
        protected Map<Object, byte[]> queryForPhotos(Set<Object> photoIds) {
            Map<Object, byte[]> photos = new HashMap<Object, byte[]>(photoIds.size());

            if (photoIds == null || photoIds.isEmpty()) {
                return photos;
            }

            String[] photoIdArgs = new String[photoIds.size()];
            int i = 0;
            for (Object id : photoIds) {
                photoIdArgs[i++] = String.valueOf(id);
            }

            // first try getting photos from Contacts
            Cursor contactCursor = null;
            try {
                contactCursor = getResolver().query(Data.CONTENT_URI, COLUMNS,
                        createInQuery(Photo._ID, photoIdArgs.length),
                        photoIdArgs, null);
                while (contactCursor.moveToNext()) {
                    Long id = contactCursor.getLong(0);
                    byte[] bytes = contactCursor.getBlob(1);
                    photoIds.remove(id);
                    photos.put(id, bytes);
                }
            } finally {
                if (contactCursor != null) {
                    contactCursor.close();
                }
            }

            Iterator<Object> iterator = photoIds.iterator();
            // then try to get the rest from Profiles
            // FIXME: try to do this in a single query, if possible
            while (iterator.hasNext()) {
                Long id = (Long) iterator.next();
                if (ContactsContract.isProfileId(id)) {
                    Cursor profileCursor = null;
                    try {
                        profileCursor = getResolver().query(
                                ContentUris.withAppendedId(Data.CONTENT_URI, id),
                                COLUMNS, null, null, null);
                        if (profileCursor != null && profileCursor.moveToFirst()) {
                            photos.put(profileCursor.getLong(0), profileCursor.getBlob(1));
                        } else {
                            // Couldn't load a photo this way either.
                            photos.put(id, null);
                        }
                    } finally {
                        if (profileCursor != null) {
                            profileCursor.close();
                        }
                    }
                } else {
                    // Not a profile photo and not found - mark the cache accordingly
                    photos.put(id, null);
                }
                iterator.remove();
            }

            return photos;
        }

        @Override
        protected Map<Object, byte[]> loadPhotos(Collection<Request> requests) {
            Map<Object, byte[]> photos = new HashMap<Object, byte[]>(requests.size());

            Set<String> addresses = new HashSet<String>();
            Set<Object> photoIds = new HashSet<Object>();
            HashMap<Long, String> photoIdMap = new HashMap<Long, String>();

            Long match;
            String emailAddress;
            for (Request request : requests) {
                emailAddress = (String) request.getKey();
                match = mPhotoIdCache.get(emailAddress);
                if (match != null) {
                    photoIds.add(match);
                    photoIdMap.put(match, emailAddress);
                } else {
                    addresses.add(emailAddress);
                }
            }

            if (addresses.size() > 0) {
                String[] selectionArgs = new String[addresses.size()];
                addresses.toArray(selectionArgs);
                Cursor photoIdsCursor = null;
                try {
                    StringBuilder query = new StringBuilder().append(Data.MIMETYPE).append("='")
                            .append(Email.CONTENT_ITEM_TYPE).append("' AND ").append(Email.DATA)
                            .append(" IN (");
                    appendQuestionMarks(query, addresses.size());
                    query.append(')');
                    photoIdsCursor = getResolver().query(Data.CONTENT_URI, DATA_COLS,
                            query.toString(), selectionArgs, null /* sortOrder */);
                    Long id;
                    String contactAddress;
                    if (photoIdsCursor != null) {
                        while (photoIdsCursor.moveToNext()) {
                            id = photoIdsCursor.getLong(DATA_PHOTO_ID_COLUMN);
                            // In case there are multiple contacts for this
                            // contact, try to always pick the one that actually
                            // has a photo.
                            if (!photoIdsCursor.isNull(DATA_PHOTO_ID_COLUMN)) {
                                contactAddress = photoIdsCursor.getString(DATA_EMAIL_COLUMN);
                                photoIds.add(id);
                                photoIdMap.put(id, contactAddress);
                                cachePhotoId(id, contactAddress);
                            }
                        }
                    }
                } finally {
                    if (photoIdsCursor != null) {
                        photoIdsCursor.close();
                    }
                }
            }
            if (photoIds != null && photoIds.size() > 0) {
                Map<Object, byte[]> photosFromIds = queryForPhotos(photoIds);

                for (Object id : photosFromIds.keySet()) {
                    byte[] bytes = photosFromIds.get(id);
                    photos.put(photoIdMap.get(id), bytes);
                }
            }

            // TODO(mindyp): this optimization assumes that contact photos don't
            // change/ update that often, and if you didn't have a matching id
            // for a contact before, you probably won't be getting it any time soon.
            for (String a : addresses) {
                if (!photoIdMap.containsValue(a)) {
                    // We couldn't find a matching photo id at all, so just
                    // cache this as needing a default image.
                    photos.put(a, null);
                }
            }

            return photos;
        }
    }
}
