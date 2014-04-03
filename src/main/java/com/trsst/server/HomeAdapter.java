/*
 * Copyright 2013 mpowers
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
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.TreeMap;

import org.apache.abdera.Abdera;
import org.apache.abdera.model.Document;
import org.apache.abdera.model.Entry;
import org.apache.abdera.model.Feed;
import org.apache.abdera.parser.ParseException;
import org.apache.abdera.protocol.server.RequestContext;
import org.apache.abdera.protocol.server.RequestContext.Scope;
import org.apache.abdera.protocol.server.context.RequestContextWrapper;

import com.trsst.Common;

/**
 * Customized adapter used to serve aggregate feeds containing global search
 * results.
 * 
 * @author mpowers
 */

public class HomeAdapter extends TrsstAdapter {

    public HomeAdapter(String feedId, Storage storage)
            throws FileNotFoundException, IOException {
        super(feedId, storage);
    }

    /**
     * Returns the current feed to service this request, fetching from the
     * current request, from local storage, or from remote peers as needed.
     */
    protected Feed currentFeed(RequestContext request) throws ParseException,
            FileNotFoundException, IOException {
        Feed feed = null;
        RequestContextWrapper wrapper = new RequestContextWrapper(request);
        // System.err.println(new Date().toString() + " "
        // + wrapper.getTargetPath());

        // fetch from request context
        feed = (Feed) wrapper.getAttribute(Scope.REQUEST, "com.trsst.Feed");
        if (feed != null) {
            // shortcut for very common case
            return feed;
        }

        feed = Abdera.getInstance().newFeed();
        feed.setId(canonicalFeedIdForQuery(request));
        feed.setTitle("Search Results");
        
        // default to one month ago in case of zero results 
        feed.setUpdated(new Date(System.currentTimeMillis()-1000*60*60*24*30));

        // if async fetch is allowed
        if (wrapper.getParameter("sync") == null) {
            if (feed != null) {
                // ingest results from relay peers asynchronously
                pullLaterFromRelay(feedId, request);
            }
        }
        
        // store in request context
        wrapper.setAttribute(Scope.REQUEST, "com.trsst.Feed", feed);
        return feed;
    }

    /**
     * Eliminates paging parameters and sorts remaining query parameters to
     * construct a feed id string of the form "?key=value&key=value"
     * 
     * @param requestment
     * @return
     */
    public static String canonicalFeedIdForQuery(RequestContext request) {
        // collate and sort query parameters
        TreeMap<String, List<String>> map = new TreeMap<String, List<String>>();
        String[] params = request.getParameterNames();
        for (String param : params) {
            map.put(param, request.getParameters(param));
        }

        // remove params used for paging
        map.remove("page");
        map.remove("count");
        map.remove("before");
        map.remove("after");

        // reassemble ordered query string
        StringBuffer buf = new StringBuffer("urn:feed:?");
        String param;
        List<String> values;
        Iterator<String> iterator = map.keySet().iterator();
        while (iterator.hasNext()) {
            param = iterator.next();
            values = map.get(param);
            for (String value : values) {
                buf.append(Common.encodeURL(param));
                buf.append('=');
                buf.append(Common.encodeURL(value));
                buf.append('&');
            }
        }
        // remove trailing ampersand
        return buf.substring(0, buf.length() - 1);
    }

    /**
     * Overridden to populate aggregate feed with global query results.
     * 
     * @return the total number of entries matching the query.
     */
    @Override
    protected int addEntriesFromStorage(Feed feed, int start, int length,
            Date after, Date before, String query, String[] mentions,
            String[] tags, String verb) {
        String[] entryIds = persistence.getEntryIds(0, length, after, before,
                query, mentions, tags, verb);
        Document<Entry> document;
        String feedId;
        long entryId;
        String urn;
        Entry entry;
        Date updated = null;
        int end = Math.min(entryIds.length, start + length);
        for (int i = start; i < end; i++) {
            urn = entryIds[i];
            feedId = urn.substring(0, urn.lastIndexOf(':'));
            entryId = Common.toEntryId(urn);
            document = getEntry(persistence, feedId, entryId);
            if (document != null) {
                entry = (Entry) document.getRoot().clone();
                if (updated == null || updated.before(entry.getUpdated())) {
                    updated = entry.getUpdated();
                }
                feed.addEntry(entry);
            } else {
                log.error("Could not find entry for id: " + feedId + " : "
                        + Long.toHexString(entryId));
            }
        }
        if (updated != null) {
            // set to date of most recent entry
            feed.setUpdated(updated);
        }
        return entryIds.length;
    }

    /**
     * Counts entries specified search parameters.
     * 
     * @return the total number of entries matching the query.
     */
    @Override
    protected int countEntriesFromStorage(Date after, Date before,
            String query, String[] mentions, String[] tags, String verb) {
        return persistence.getEntryCount(after, before, query, mentions, tags,
                verb);
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory
            .getLogger(HomeAdapter.class);

}