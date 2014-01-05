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

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;

/**
 * Storage handles all read and write persistence for a trsst server.
 * Implementors will only need to implement this class in order to support
 * different types of persistence (flat file, relational db, object db, etc.)
 * This interface is intended to require no additional dependencies on the trsst
 * or abdera frameworks.
 * 
 * @author mpowers
 */
public interface Storage {

    /**
     * Returns feed ids with content hosted on this server. Feeds must be
     * ordered by most recent update.
     * 
     * @param start
     *            the start index from which to return results; if exceeds
     *            bounds of available results, zero results are returned.
     * @param length
     *            the maximum number of results to return; servers may return
     *            fewer results.
     * @return the specified feed ids hosted on this server; may be empty but
     *         not null.
     */
    String[] getFeedIds(int start, int length);

    /**
     * Returns categories mentioned in content hosted on this server. Categories
     * should be ordered by most popular or recently used, or a combination of
     * both ("trending").
     * 
     * @param start
     *            the start index from which to return results; if exceeds
     *            bounds of available results, zero results are returned.
     * @param length
     *            the maximum number of results to return; servers may return
     *            fewer results.
     * @return the specified trending categories; may be empty but not null.
     */
    String[] getCategories(int start, int length);

    /**
     * Returns the total number of entries for the specified feed id, or -1 if
     * the feed id is unrecognized or unsupported.
     * 
     * @param feedId
     *            the specified feed.
     * @return the total number of entries for the specified feed, or -1 if not
     *         found.
     */
    int getEntryCountForFeedId(String feedId);

    /**
     * Return a string array containing entry ids for the specified feed id,
     * within the specified range, and matching the specified query, ordered
     * with most recent entries first. The query is a space-delimited string of
     * words to be matched against an entries' tags, mentions, title, and
     * summary, and only entries matching all terms are returned.
     * 
     * @param feedId
     *            the specified feed.
     * @param offset
     *            the number of entries to skip starting with the most recent
     *            entry.
     * @param length
     *            the number of entries to return after skipping the offset
     *            entries.
     * @param after
     *            (optional) restricts results to those entries posted on or
     *            after the specified date, or null if no restriction.
     * @param before
     *            (optional) restricts results to those entries posted on or
     *            before the specified date, or null if no restriction.
     * @param query
     *            (optional) a space-delimited string of query terms, or null if
     *            for no query; query language is implementation-dependent, but
     *            at minimum a single-term search returns only results that
     *            containing the specified term.
     * @param mentions
     *            (optional) restricts results to those entries that contain all
     *            of the specified mentions
     * @param tags
     *            (optional) restricts results to those entries that contain all
     *            of the specified tags
     * @param verb
     *            (optional) restricts results to those entries that contain the
     *            specified verb
     * @return an array containing the matching entry ids; will contain no more
     *         entries and the specified length, but may contain fewer entries,
     *         or zero entries; null if error or feed not found.
     */
    long[] getEntryIdsForFeedId(String feedId, int start, int length,
            Date after, Date before, String query, String[] mentions,
            String[] tags, String verb);

    /**
     * Returns the contents of the unmodified feed element which was previously
     * passed to updateFeed for the specified feed; otherwise throws
     * FileNotFoundException.
     * 
     * @param feedId
     *            the specified feed.
     * @return a signed feed element and child elements but excluding entry
     *         elements.
     * @throws IOException
     *             if an error occurs obtaining the entry data, such as if the specified feed does not exist on this server.
     */
    String readFeed(String feedId) throws IOException;

    /**
     * Receives the contents of a signed feed element to be stored and
     * associated with the specified feed. The retured string contains a signed
     * feed element and holds all meta-data attributes associated with the feed.
     * These contents may be inspected, analyzed, and indexed, but must be
     * returned unmodifed to callers of readFeed() so the signature remains
     * intact. Note that the feed element DOES NOT contain entry elements.
     * 
     * @param feedId
     *            the specified feed.
     * @param lastUpdated
     *            the datetime when this feed says it was last updated; used for
     *            time range queries
     * @param feed
     *            the contents to be persisted.
     * @throws IOException
     *             if a error occurs persisting the entry data.
     */
    void updateFeed(String feedId, Date lastUpdated, String feed)
            throws IOException;

    /**
     * Returns the contents of a signed entry element for the specified feed
     * which was previously passed to updateFeedEntry.
     * 
     * @param feedId
     *            the specified feed.
     * @param entryId
     *            the desired entry for the specified feed.
     * @return a signed entry element.
     * @throws IOException
     *             if a error occurs obtaining the entry data, such as if the specified entry does not exist.
     */
    String readEntry(String feedId, long entryId) throws IOException;

    /**
     * Receives the contents of a signed entry element to be stored and
     * associated with the specified feed and unique identifier for later
     * retrieval by readFeedEntry(). These contents may be inspected, analyzed,
     * and indexed, but must be returned unmodifed to callers of readEntry() so
     * the signature remains intact.
     * 
     * @param feedId
     *            the specified feed.
     * @param entryId
     *            the unique identifier for the entry to be persisted.
     * @param publishDate
     *            the datetime when this entry says it was or will be published;
     *            used for date/time range queries
     * @param entry
     *            an entry element whose contents are to be persisted.
     * @throws IOException
     *             if a error occurs persisting the entry data.
     */
    void updateEntry(String feedId, long entryId, Date publishDate,
            String entry) throws IOException;

    /**
     * Delete an existing entry for the specified feed.
     * 
     * @param feedId
     *            the specified feed.
     * @param entryId
     *            the desired entry for the specified feed.
     * @throws IOException
     *             if a error occurs while deleting the entry data such as if the specified entry does not exist.
     */
    void deleteEntry(String feedId, long entryId) throws IOException;

    /**
     * Returns the mime-type of the contents of the resource data for the
     * specified entry for the specified feed, if known. If not known, returns
     * null.
     * 
     * @param feedId
     *            the specified feed.
     * @param entryId
     *            the specified entry.
     * @param resourceId
     *            the desired resource id for the specified feed and entry.
     * @return the mime type of the resource, or null if not known.
     * @throws IOException
     *             if a error occurs obtaining the resource data such as if the specified resource does not exist on this server.
     */
    String readFeedEntryResourceType(String feedId, long entryId,
            String resourceId) throws IOException;

    /**
     * Obtains an input stream to read the contents of the resource data for the
     * specified entry for the specified feed. Callers must close the input
     * stream when finished.
     * 
     * @param feedId
     *            the specified feed.
     * @param entryId
     *            the specified entry.
     * @param resourceId
     *            the desired resource id for the specified feed and entry.
     * @return an input stream to read the contents of the resource.
     * @throws IOException
     *             if a error occurs obtaining the resource data such as if the specified entry does not exist.
     */
    InputStream readFeedEntryResource(String feedId, long entryId, String resourceId) throws IOException;

    /**
     * Stores a binary resource for the specified feed and entry by reading the
     * specified input stream and persisting the contents for later retrieval by
     * readFeedEntryResource(). Implementors must close the input stream when
     * finished.
     * 
     * @param feedId
     *            the specified feed.
     * @param entryId
     *            the specified entry.
     * @param resourceId
     *            the desired resource id for the specified feed and entry.
     * @param mimeType
     *            the mime type of the data if known, otherwise null.
     * @param publishDate
     *            the datetime when the associated entry says it was or will be
     *            published; used for date/time range queries
     * @param data
     *            an input stream whose contents are to be persisted.
     * @throws IOException
     *             if a error occurs persisting the resource data.
     */
    void updateFeedEntryResource(String feedId, long entryId,
            String resourceId, String mimeType, Date publishDate,
            InputStream data) throws IOException;

    /**
     * Delete an existing resource for the specified feed and entry.
     * 
     * @param feedId
     *            the specified feed.
     * @param entryId
     *            the specified entry.
     * @param resourceId
     *            the desired resource id for the specified feed and entry.
     * @throws IOException
     *             if a error occurs while deleting the resource data.
     */
    void deleteFeedEntryResource(String feedId, long entryId,
            String resourceId) throws IOException;

}