/*
 * Copyright 2014 mpowers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.trsst.server;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.concurrent.ConcurrentMap;

import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;

/**
 * A simple passthrough that caches read operations.
 * 
 * @author mpowers
 */
public class CachingStorage implements Storage {

    /**
     * Persistent storage delegate used to fetch items not in cache and to
     * passthrough write operations.
     */
    private Storage persistentStorage;

    /**
     * Persistent storage delegate used to fetch items not in cache and to
     * passthrough write operations.
     */
    private ConcurrentMap<String, Object> cache;

    /**
     * Manages index and calls to the specified storage delegate to handle
     * individual feed, entry, and resource persistence.
     * 
     * @param delegate
     * @throws IOException
     */
    public CachingStorage(Storage delegate) throws IOException {
        persistentStorage = delegate;
        cache = new ConcurrentLinkedHashMap.Builder<String, Object>()
                .maximumWeightedCapacity(256).build();

    }

    private static char DELIMITER = 0;

    private static final String tokenize(Object... args) {
        StringBuffer buf = new StringBuffer();
        for (Object arg : args) {
            buf.append(arg).append(DELIMITER);
        }
        return buf.toString();
    }

    private static Object NOT_FOUND = "NOT_FOUND";

    private Object get(String token) {
        if (cache.containsKey(token)) {
            return cache.get(token);
        }
        return NOT_FOUND;
    }

    private void put(String token, Object value) {
        cache.put(token, value);
    }

    private void purge(String prefix) {
        // purge all keys with prefix
        for (String key : cache.keySet()) {
            if (key.startsWith(prefix)) {
                cache.remove(key);
            }
        }
    }

    public String[] getFeedIds(int start, int length) {
        String token = tokenize("getFeedIds", start, length);
        Object result = get(token);
        if (result == NOT_FOUND) {
            result = persistentStorage.getFeedIds(start, length);
            put(token, result);
        }
        return (String[]) result;
    }

    public String[] getCategories(int start, int length) {
        String token = tokenize("getCategories", start, length);
        Object result = get(token);
        if (result == NOT_FOUND) {
            result = persistentStorage.getCategories(start, length);
            put(token, result);
        }
        return (String[]) result;
    }

    public int getEntryCount(Date after, Date before, String query,
            String[] mentions, String[] tags, String verb) {
        return getEntryCountForFeedId(null, after, before, query, mentions,
                tags, verb);
    }

    public int getEntryCountForFeedId(String feedId, Date after, Date before,
            String search, String[] mentions, String[] tags, String verb) {
        String token = tokenize(feedId, "getEntryCountForFeedId", after,
                before, search, mentions, tags, verb);
        Object result = get(token);
        if (result == NOT_FOUND) {
            result = persistentStorage.getEntryCountForFeedId(feedId, after,
                    before, search, mentions, tags, verb);
            put(token, result);
        }
        return ((Integer) result).intValue();
    }

    public String[] getEntryIds(int start, int length, Date after, Date before,
            String search, String[] mentions, String[] tags, String verb) {
        String token = tokenize("getEntryIds", start, length, after, before,
                search, mentions, tags, verb);
        Object result = get(token);
        if (result == NOT_FOUND) {
            result = persistentStorage.getEntryIds(start, length, after,
                    before, search, mentions, tags, verb);
            put(token, result);
        }
        return (String[]) result;
    }

    public long[] getEntryIdsForFeedId(String feedId, int start, int length,
            Date after, Date before, String search, String[] mentions,
            String[] tags, String verb) {
        String token = tokenize(feedId, "getEntryIdsForFeedId", start, length,
                after, before, search, mentions, tags, verb);
        Object result = get(token);
        if (result == NOT_FOUND) {
            result = persistentStorage.getEntryIdsForFeedId(feedId, start,
                    length, after, before, search, mentions, tags, verb);
            put(token, result);
        }
        return (long[]) result;
    }

    public String readFeed(String feedId) throws FileNotFoundException,
            IOException {
        String token = tokenize(feedId, "readFeed");
        Object result = get(token);
        if (result == NOT_FOUND) {
            result = persistentStorage.readFeed(feedId);
            put(token, result);
        }
        return (String) result;
    }

    public void updateFeed(String feedId, Date lastUpdated, String content)
            throws IOException {
        persistentStorage.updateFeed(feedId, lastUpdated, content);
        purge(feedId);
    }

    public String readEntry(String feedId, long entryId)
            throws FileNotFoundException, IOException {
        String token = tokenize(feedId, "readEntry", entryId);
        Object result = get(token);
        if (result == NOT_FOUND) {
            result = persistentStorage.readEntry(feedId, entryId);
            put(token, result);
        }
        return (String) result;
    }

    public void updateEntry(String feedId, long entryId, Date publishDate,
            String content) throws IOException {
        persistentStorage.updateEntry(feedId, entryId, publishDate, content);
        purge(feedId);
    }

    public void deleteEntry(String feedId, long entryId)
            throws FileNotFoundException, IOException {
        persistentStorage.deleteEntry(feedId, entryId);
        purge(feedId);
    }

    public String readFeedEntryResourceType(String feedId, long entryId,
            String resourceId) throws FileNotFoundException, IOException {
        String token = tokenize(feedId, "readFeedEntryResourceType", entryId,
                resourceId);
        Object result = get(token);
        if (result == NOT_FOUND) {
            result = persistentStorage.readFeedEntryResourceType(feedId,
                    entryId, resourceId);
            put(token, result);
        }
        return (String) result;
    }

    public InputStream readFeedEntryResource(String feedId, long entryId,
            String resourceId) throws FileNotFoundException, IOException {
        // don't cache binary content
        return persistentStorage.readFeedEntryResource(feedId, entryId,
                resourceId);
    }

    public void updateFeedEntryResource(String feedId, long entryId,
            String resourceId, String mimeType, Date publishDate, byte[] data)
            throws IOException {
        persistentStorage.updateFeedEntryResource(feedId, entryId, resourceId,
                mimeType, publishDate, data);
    }

    public void deleteFeedEntryResource(String feedId, long entryId,
            String resourceId) throws IOException {
        persistentStorage.deleteFeedEntryResource(feedId, entryId, resourceId);
    }

}
