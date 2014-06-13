package com.trsst.server;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import javax.xml.namespace.QName;

import org.apache.abdera.Abdera;
import org.apache.abdera.i18n.iri.IRI;
import org.apache.abdera.model.Category;
import org.apache.abdera.model.Element;
import org.apache.abdera.model.Entry;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.flexible.core.QueryNodeException;
import org.apache.lucene.queryparser.flexible.standard.StandardQueryParser;
import org.apache.lucene.queryparser.flexible.standard.config.StandardQueryConfigHandler;
import org.apache.lucene.queryparser.flexible.standard.parser.ParseException;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.NumericRangeFilter;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;
import org.w3c.tidy.Tidy;

import com.trsst.Common;
import com.trsst.client.Client;

/**
 * Manages storage and indexing of feed and entry documents, and delegates
 * storage of resources to another Storage instance.
 * 
 * @author mpowers
 */
public class LuceneStorage implements Storage {

    /**
     * Shared abdera instance.
     */
    private Abdera abdera;

    /**
     * Deletable storage delegate: used for caching feeds fetched from other
     * servers. Basically, if this storage went away, it would be no big deal.
     */
    private Storage cacheStorage;

    /**
     * Persistent storage delegate: used for feeds managed by this server. This
     * is basically the user's primary backup of all entries created.
     */
    private Storage persistentStorage;

    /*
     * Lucene readers/writers are thread-safe and shared instances are
     * recommended.
     */

    private IndexWriter writer;
    private IndexReader reader;
    private Analyzer analyzer;

    /**
     * Default constructor manages individual feed, entry, and resource
     * documents with a FileStorage.
     * 
     * @throws IOException
     */
    public LuceneStorage() throws IOException {
        this(new FileStorage());
    }

    /**
     * Manages index and calls to the specified storage delegate to handle
     * individual feed, entry, and resource persistence.
     * 
     * @param delegate
     * @throws IOException
     */
    public LuceneStorage(Storage delegate) throws IOException {
        this(delegate, null);
    }

    /**
     * Manages index and calls to the specified storage delegate to handle
     * individual feed, entry, and resource persistence. Any feeds managed by
     * this server will call to persistent storage rather than cache storage.
     * 
     * @param delegate
     * @throws IOException
     */
    public LuceneStorage(Storage cache, Storage persistent) throws IOException {
        cacheStorage = cache;
        persistentStorage = persistent;
        abdera = Abdera.getInstance();
        Directory dir = FSDirectory.open(new File(Common.getServerRoot(),
                "entry.idx"));
        analyzer = new StandardAnalyzer(Version.LUCENE_46);

        IndexWriterConfig iwc = new IndexWriterConfig(Version.LUCENE_46,
                analyzer);
        iwc.setOpenMode(OpenMode.CREATE_OR_APPEND);
        writer = new IndexWriter(dir, iwc);
        writer.commit();
        refreshReader();
    }

    private void refreshReader() throws IOException {
        reader = DirectoryReader.open(writer, true);
    }

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
     * @return the specified feed ids hosted on this server.
     */
    public String[] getFeedIds(int start, int length) {
        return persistentStorage.getFeedIds(start, length);
    }

    private boolean isManaged(String feedId) {
        String[] feedIds = getFeedIds(0, 100);
        for (String id : feedIds) {
            if (id.equals(feedId)) {
                return true;
            }
        }
        return false;
    }

    private Storage getStorage(String feedId) {
        if (persistentStorage == null) {
            return cacheStorage;
        }
        if (isManaged(feedId)) {
            return persistentStorage;
        }
        return cacheStorage;
    }

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
     * @return the specified trending categories.
     */
    public String[] getCategories(int start, int length) {
        // TODO: implement category tracking
        // return most frequent categories for the past 100 or 1000 entries
        return new String[0];
    }

    public int getEntryCount(Date after, Date before, String query,
            String[] mentions, String[] tags, String verb) {
        return getEntryCountForFeedId(null, after, before, query, mentions,
                tags, verb);
    }

    public int getEntryCountForFeedId(String feedId, Date after, Date before,
            String search, String[] mentions, String[] tags, String verb) {
        try {
            Filter filter = buildRangeFilter(after, before);
            Query query = buildTextQuery(feedId, search, mentions, tags, verb);
            CountCollector collector = new CountCollector();
            new IndexSearcher(reader).search(query, filter, collector);
            return collector.getCount();
        } catch (IOException e) {
            log.error("Unexpected error getting entry count for feed: "
                    + feedId, e);
        } catch (QueryNodeException e) {
            log.error("Unexpected error executing count query for feed: "
                    + feedId, e);
        }
        return -1;
    }

    public String[] getEntryIds(int start, int length, Date after, Date before,
            String query, String[] mentions, String[] tags, String verb) {
        return _getEntryIdsForFeedId(null, start, length, after, before, query,
                mentions, tags, verb);
    }

    public long[] getEntryIdsForFeedId(String feedId, int start, int length,
            Date after, Date before, String query, String[] mentions,
            String[] tags, String verb) {
        String[] ids = _getEntryIdsForFeedId(feedId, start, length, after,
                before, query, mentions, tags, verb);
        long[] result = null;
        if (ids != null) {
            result = new long[ids.length];
            int i = 0;
            int offset = feedId.length() + 1; // entry keys contain feed id
            for (String id : ids) {
                result[i++] = Long.parseLong(id.substring(offset), 16);
            }
        }
        return result;
    }

    private String[] _getEntryIdsForFeedId(String feedId, int start,
            int length, Date after, Date before, String search,
            String[] mentions, String[] tags, String verb) {
        try {
            Filter filter = buildRangeFilter(after, before);
            Query query = buildTextQuery(feedId, search, mentions, tags, verb);
            TopDocs hits = new IndexSearcher(reader).search(query, filter,
                    start + length, new Sort(new SortField("updated",
                            SortField.Type.LONG, true)));
            String[] result = new String[Math.min(length, hits.totalHits)];
            int i = 0;
            String id;
            int replace;
            Set<String> fields = new HashSet<String>();
            fields.add("entry"); // we only need the entry field
            for (ScoreDoc e : hits.scoreDocs) {
                id = new IndexSearcher(reader).doc(e.doc).get("entry");
                replace = id.lastIndexOf('-');
                if (replace != -1) {
                    id = id.substring(0, replace) + ':'
                            + id.substring(replace + 1);
                }
                result[i++] = id;
            }
            return result;
        } catch (IOException e) {
            log.error("Unexpected error getting query for feed: " + feedId, e);
        } catch (QueryNodeException e) {
            log.error("Unexpected error executing query for feed: " + feedId, e);
        }
        return null;
    }

    private Filter buildRangeFilter(Date after, Date before) {
        if (after == null && before == null) {
            return null;
        }

        long afterTime;
        if (after != null) {
            afterTime = after.getTime();
        } else {
            // all entries are after this key
            afterTime = Long.MIN_VALUE;
        }
        long beforeTime;
        if (before != null) {
            beforeTime = before.getTime();
        } else {
            // all entries are before this key
            beforeTime = Long.MAX_VALUE;
        }
        return NumericRangeFilter.newLongRange("updated", afterTime,
                beforeTime, false, false);
    }

    private Query buildTextQuery(String feedId, String search,
            String[] mentions, String[] tags, String verb)
            throws QueryNodeException {
        if (search == null) {
            search = "";
        }
        // feedId = "M9Dvwqp4GcRJe6gh7p73bCcQk8dKLG19z";
        // search = "feed:\"HSzp9eneHcqsp4Vdt9pMfP1Qy83FZZwmE\"";
        if (verb != null) {
            search = search + " verb:" + verb;
        }
        if (tags != null) {
            for (String tag : tags) {
                tag = tag.trim();
                if (tag.startsWith(Common.FEED_URN_PREFIX)) {
                    tag = tag.substring(Common.FEED_URN_PREFIX.length());
                }
                if (tag.startsWith(Common.ENTRY_URN_PREFIX)) {
                    tag = tag.substring(Common.ENTRY_URN_PREFIX.length());
                }
                search = search + " tag:\"" + tag.toLowerCase() + "\"";
            }
        }
        if (mentions != null) {
            for (String mention : mentions) {
                mention = mention.trim();
                if (mention.startsWith(Common.ACCOUNT_URN_PREFIX)) {
                    int index = mention.indexOf(Common.ACCOUNT_URN_FEED_PREFIX);
                    if (index != -1) {
                        // feed id instead
                        String id = mention.substring(index
                                + Common.ACCOUNT_URN_FEED_PREFIX.length());
                        search = search + " tag:\"" + id + "\"";
                        // truncate feed id and continue
                        mention = mention.substring(0, index);
                    }
                    mention = mention.substring(Common.ACCOUNT_URN_PREFIX
                            .length());
                }
                if (mention.startsWith(Common.FEED_URN_PREFIX)) {
                    mention = mention
                            .substring(Common.FEED_URN_PREFIX.length());
                }
                if (mention.startsWith(Common.ENTRY_URN_PREFIX)) {
                    mention = mention.substring(Common.ENTRY_URN_PREFIX
                            .length());
                }
                // mentions treated as tags in index
                search = search + " tag:\"" + mention + "\"";
            }
        }
        if (feedId != null) {
            search = "feed:\"" + feedId + "\"" + search;
        }
        if (search.trim().length() == 0) {
            log.trace("No search parameters: " + search);
            search = "*"; // return everything
        }
        StandardQueryParser parser = new StandardQueryParser();
        parser.setDefaultOperator(StandardQueryConfigHandler.Operator.AND);
        try {
            return parser.parse(search, "text");
        } catch (ParseException se) {
            log.error("Could not parse query: " + search);
            throw se;
        }
    }

    /**
     * Returns the contents of the unmodified feed element which was previously
     * passed to updateFeed for the specified feed; otherwise throws
     * FileNotFoundException.
     * 
     * @param feedId
     *            the specified feed.
     * @return a signed feed element and child elements but excluding entry
     *         elements.
     * @throws FileNotFoundException
     *             if the specified feed does not exist on this server.
     * @throws IOException
     *             if an error occurs obtaining the entry data.
     */
    public String readFeed(String feedId) throws FileNotFoundException,
            IOException {
        return getStorage(feedId).readFeed(feedId);
    }

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
    public void updateFeed(String feedId, Date lastUpdated, String content)
            throws IOException {
        try {
            // NOTE: not yet sure if we want to index feeds
            // as we haven't exposed a way to search them
            // Feed feed = (Feed) abdera.getParser()
            // .parse(new StringReader(content)).getRoot();
            // if (feed.getTitle() != null) {
            // Document document = new Document();
            // document.add(new StringField("feed", feedId, Field.Store.NO));
            // document.add(new TextField("title", feed.getTitle(),
            // Field.Store.NO));
            // document.add(new TextField("subtitle", feed.getSubtitle(),
            // Field.Store.NO));
            // Person author = feed.getAuthor();
            // if (author != null) {
            // if (author.getName() != null) {
            // document.add(new TextField("name", author.getName(),
            // Field.Store.NO));
            // }
            // if (author.getEmail() != null) {
            // document.add(new StringField("address", author
            // .getEmail(), Field.Store.NO));
            // }
            // }
            //
            getStorage(feedId).updateFeed(feedId, lastUpdated, content);
            // feedWriter.updateDocument(new Term("feed", feedId), document);
            // }

        } catch (Throwable t) {
            log.error(
                    "Error from update feed: " + feedId + " : " + lastUpdated,
                    t);
            throw new IOException("Could not parse input for: " + feedId
                    + " : " + t.getMessage());
        }
    }

    /**
     * Returns the contents of a signed entry element for the specified feed
     * which was previously passed to updateFeedEntry.
     * 
     * @param feedId
     *            the specified feed.
     * @param entryId
     *            the desired entry for the specified feed.
     * @return a signed entry element.
     * @throws FileNotFoundException
     *             if the specified entry does not exist.
     * @throws IOException
     *             if a error occurs obtaining the entry data.
     */
    public String readEntry(String feedId, long entryId)
            throws FileNotFoundException, IOException {
        return getStorage(feedId).readEntry(feedId, entryId);
    }

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
    public void updateEntry(String feedId, long entryId, Date publishDate,
            String content) throws IOException {
        try {
            Entry entry = (Entry) abdera.getParser()
                    .parse(new StringReader(content)).getRoot();

            // we also accumulate categories, mentions, and verbs into
            // a single combined multivalue string index
            Set<String> tags = new HashSet<String>();

            // get verb
            String verb = null; // "post" is default verb
            Element verbElement = entry.getExtension(new QName(
                    "http://activitystrea.ms/spec/1.0/", "verb", "activity"));
            if (verbElement != null) {
                if (verbElement.getText() != null) {
                    verb = verbElement.getText().trim().toLowerCase();
                    while (verb.length() > 0
                            && (verb.charAt(0) == '#' || verb.charAt(0) == '@')) {
                        // strip our "special" characters
                        verb = verb.substring(1);
                    }
                }
            }
            if (verb == null || verb.length() == 0) {
                verb = "post"; // "post" is default verb
            }
            tags.add(verb);

            // get categories
            List<Category> categories = entry.getCategories();
            if (categories != null) {
                for (Category e : categories) {
                    IRI scheme = e.getScheme();
                    if (scheme != null
                            && (Common.TAG_URN.equals(scheme.toString()) || Common.TAG_URN_LEGACY
                                    .equals(scheme.toString()))) {
                        if (e.getTerm() != null) {
                            tags.add('#' + e.getTerm().trim().toLowerCase());
                        }
                    } else if (scheme != null
                            && (Common.MENTION_URN.equals(scheme.toString()) || Common.MENTION_URN_LEGACY
                                    .equals(scheme.toString()))) {
                        String mention = e.getTerm();
                        if (mention != null) {
                            mention = mention.trim();
                            if (mention.startsWith(Common.ACCOUNT_URN_PREFIX)) {
                                int index = mention
                                        .indexOf(Common.ACCOUNT_URN_FEED_PREFIX);
                                if (index != -1) {
                                    // feed id instead
                                    String id = mention.substring(index
                                            + Common.ACCOUNT_URN_FEED_PREFIX
                                                    .length());
                                    tags.add('@' + id);
                                    // truncate feed id and continue
                                    mention = mention.substring(0, index);
                                }
                                mention = mention
                                        .substring(Common.ACCOUNT_URN_PREFIX
                                                .length());
                            }
                            if (mention.startsWith(Common.FEED_URN_PREFIX)) {
                                mention = mention
                                        .substring(Common.FEED_URN_PREFIX
                                                .length());
                            }
                            if (mention.startsWith(Common.ENTRY_URN_PREFIX)) {
                                mention = mention
                                        .substring(Common.ENTRY_URN_PREFIX
                                                .length());
                            }
                            tags.add('@' + mention);
                        }
                    }
                }
            }

            // convert to list and persist
            List<String> converted = new LinkedList<String>();
            for (String tag : tags) {
                converted.add(tag);
            }

            // extract fields for full-text search index
            Document document = new Document();
            StringBuffer text = new StringBuffer();
            document.add(new StringField("entry", getEntryKeyString(feedId,
                    entryId), Field.Store.YES));
            text.append(entryId).append(' ');
            document.add(new StringField("feed", feedId, Field.Store.NO));
            text.append(feedId).append(' ');
            document.add(new StringField("verb", verb, Field.Store.NO));
            text.append(verb).append(' ');
            document.add(new LongField("updated", entryId, Field.Store.NO));
            text.append(verb).append(' ');

            if (entry.getTitle() != null) {
                String title = entry.getTitle().toLowerCase();
                document.add(new TextField("title", title, Field.Store.NO));
                text.append(title).append(' ');
            }
            if (entry.getSummary() != null) {
                String summary = extractTextFromHtml(entry.getSummary())
                        .toLowerCase();
                // System.out.println("extracting: " + summary);
                document.add(new TextField("summary", summary, Field.Store.NO));
                text.append(summary).append(' ');
            }
            tags.remove(verb); // don't treat verb as tag in full-text search
            for (String tag : tags) {
                tag = tag.substring(1); // remove @ or #
                document.add(new StringField("tag", tag, Field.Store.NO));
                text.append(tag).append(' ');
            }
            document.add(new TextField("text", text.toString(), Field.Store.NO));

            // persist the document
            getStorage(feedId).updateEntry(feedId, entryId, publishDate,
                    content);
            writer.updateDocument(
                    new Term("entry", getEntryKeyString(feedId, entryId)),
                    document);
            writer.commit();
            refreshReader();
        } catch (Throwable t) {
            log.error("Error from update entry: " + feedId + " : " + entryId, t);
            throw new IOException("Could not parse input for: "
                    + getEntryKeyString(feedId, entryId) + " : "
                    + t.getMessage());
        }
    }

    // borrowed from lai-xin-chu: http://stackoverflow.com/questions/12576119
    private String extractTextFromHtml(String html) {
        Tidy tidy = new Tidy();
        tidy.setQuiet(true);
        tidy.setShowWarnings(false);
        org.w3c.dom.Document root = tidy.parseDOM(new StringReader(html), null);
        return getText(root.getDocumentElement());
    }

    // borrowed from lai-xin-chu: http://stackoverflow.com/questions/12576119
    private String getText(Node node) {
        NodeList children = node.getChildNodes();
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            switch (child.getNodeType()) {
            case Node.ELEMENT_NODE:
                sb.append(getText(child));
                sb.append(" ");
                break;
            case Node.TEXT_NODE:
                sb.append(((Text) child).getData());
                break;
            }
        }
        return sb.toString();
    }

    /**
     * Delete an existing entry for the specified feed.
     * 
     * @param feedId
     *            the specified feed.
     * @param entryId
     *            the desired entry for the specified feed.
     * @throws FileNotFoundException
     *             if the specified entry does not exist.
     * @throws IOException
     *             if a error occurs while deleting the entry data.
     */
    public void deleteEntry(String feedId, long entryId)
            throws FileNotFoundException, IOException {
        try {
            writer.deleteDocuments(new Term("entry", getEntryKeyString(feedId,
                    entryId)));
            writer.commit();
            refreshReader();
        } catch (Throwable t) {
            log.error("Unexpected error from delete entry: " + feedId + " : "
                    + entryId, t);
            throw new IOException("Unexpected error while deleting: "
                    + getEntryKeyString(feedId, entryId) + " : "
                    + t.getMessage());
        }
        getStorage(feedId).deleteEntry(feedId, entryId);
    }

    private static final String getEntryKeyString(String feedId, long entityId) {
        return feedId + '-' + Long.toHexString(entityId);
    }

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
     * @throws FileNotFoundException
     *             if the specified resource does not exist on this server.
     * @throws IOException
     *             if a error occurs obtaining the resource data.
     */
    public String readFeedEntryResourceType(String feedId, long entryId,
            String resourceId) throws FileNotFoundException, IOException {
        return getStorage(feedId).readFeedEntryResourceType(feedId, entryId,
                resourceId);
    }

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
     * @throws FileNotFoundException
     *             if the specified entry does not exist.
     * @throws IOException
     *             if a error occurs obtaining the resource data.
     */
    public InputStream readFeedEntryResource(String feedId, long entryId,
            String resourceId) throws FileNotFoundException, IOException {
        return getStorage(feedId).readFeedEntryResource(feedId, entryId,
                resourceId);
    }

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
    public void updateFeedEntryResource(String feedId, long entryId,
            String resourceId, String mimeType, Date publishDate, byte[] data)
            throws IOException {
        getStorage(feedId).updateFeedEntryResource(feedId, entryId, resourceId,
                mimeType, publishDate, data);
    }

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
    public void deleteFeedEntryResource(String feedId, long entryId,
            String resourceId) throws IOException {
        getStorage(feedId).deleteFeedEntryResource(feedId, entryId, resourceId);
    }

    private final static org.slf4j.Logger log = org.slf4j.LoggerFactory
            .getLogger(Client.class);

    private static class CountCollector extends Collector {
        int count;

        @Override
        public void setScorer(Scorer scorer) throws IOException {
            // ignore
        }

        @Override
        public void collect(int doc) throws IOException {
            count++;
        }

        @Override
        public void setNextReader(AtomicReaderContext context)
                throws IOException {
            // ignore
        }

        @Override
        public boolean acceptsDocsOutOfOrder() {
            return true;
        }

        public int getCount() {
            return count;
        }

    }

}
