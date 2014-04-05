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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.PublicKey;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

import javax.servlet.http.HttpUtils;
import javax.xml.crypto.dsig.XMLSignatureException;
import javax.xml.namespace.QName;

import org.apache.abdera.Abdera;
import org.apache.abdera.ext.rss.RssConstants;
import org.apache.abdera.i18n.iri.IRI;
import org.apache.abdera.i18n.iri.IRISyntaxException;
import org.apache.abdera.i18n.templates.Template;
import org.apache.abdera.model.AtomDate;
import org.apache.abdera.model.Category;
import org.apache.abdera.model.Content;
import org.apache.abdera.model.Document;
import org.apache.abdera.model.Element;
import org.apache.abdera.model.Entry;
import org.apache.abdera.model.Feed;
import org.apache.abdera.model.Link;
import org.apache.abdera.model.Person;
import org.apache.abdera.model.Text;
import org.apache.abdera.parser.ParseException;
import org.apache.abdera.protocol.Response.ResponseType;
import org.apache.abdera.protocol.client.AbderaClient;
import org.apache.abdera.protocol.client.ClientResponse;
import org.apache.abdera.protocol.client.RequestOptions;
import org.apache.abdera.protocol.server.ProviderHelper;
import org.apache.abdera.protocol.server.RequestContext;
import org.apache.abdera.protocol.server.RequestContext.Scope;
import org.apache.abdera.protocol.server.ResponseContext;
import org.apache.abdera.protocol.server.TargetType;
import org.apache.abdera.protocol.server.context.MediaResponseContext;
import org.apache.abdera.protocol.server.context.RequestContextWrapper;
import org.apache.abdera.protocol.server.context.ResponseContextException;
import org.apache.abdera.protocol.server.context.StreamWriterResponseContext;
import org.apache.abdera.security.AbderaSecurity;
import org.apache.abdera.security.Signature;
import org.apache.abdera.security.SignatureOptions;
import org.apache.abdera.util.Constants;
import org.apache.abdera.util.EntityTag;
import org.apache.abdera.util.MimeTypeHelper;
import org.apache.abdera.writer.StreamWriter;
import org.apache.commons.codec.binary.Base64;

import com.trsst.Common;

/**
 * Trsst-specific extensions to atompub, which mainly consists of accepting
 * Feeds instead of Entries, and validating that all Entries and Feeds are
 * signed.
 * 
 * Servers don't deal with encryption or private keys at all.
 * 
 * All persistence is delegated to an instance of Storage.
 * 
 * Callers may serve multiple request for the same feed id to the same
 * TrsstAdapter and instances might be retained in cache, so you need to be
 * thread-safe and resource-friendly.
 * 
 * @author mpowers
 */

@SuppressWarnings("deprecation")
public class TrsstAdapter extends AbstractMultipartAdapter {

    private final static Template paging_template = new Template(
            "{collection}?{-join|&|q,verb,mention,tag,before,after,count,page}");

    protected String feedId;
    protected Storage persistence;
    protected Map<String, String> accepts;

    /**
     * Callers may serve multiple request for the same feed id to the same
     * TrsstAdapter and instances might be retained in cache, so you need to be
     * thread-safe and resource-friendly.
     * 
     * @param id
     *            the feed id in question
     * @param storage
     *            the persistence engine
     * @throws FileNotFoundException
     *             if requested feed is not known to this server
     * @throws IOException
     *             for other kinds of persistence issues
     */
    public TrsstAdapter(String feedId, Storage storage)
            throws FileNotFoundException, IOException {
        this.persistence = storage;
        this.feedId = feedId;
    }

    /**
     * Returns the current feed to service this request, fetching from the
     * current request, from local storage, or from remote peers as needed.
     */
    protected Feed currentFeed(RequestContext request) throws ParseException,
            FileNotFoundException, IOException {
        Feed feed = null;
        RequestContextWrapper wrapper = new RequestContextWrapper(request);

        // fetch from request context
        feed = (Feed) wrapper.getAttribute(Scope.REQUEST, "com.trsst.Feed");
        if (feed != null) {
            // shortcut for very common case
            return feed;
        }

        System.err.println(new Date().toString() + " "
                + wrapper.getTargetPath());

        // if async fetch is allowed
        if (wrapper.getParameter("sync") == null) {
            // return latest from local storage
            feed = fetchFeedFromStorage(feedId, persistence);
            if (feed != null) {
                // trigger async fetch in case we're stale
                pullLaterFromRelay(feedId, request);
                // pullFromRelay(request);
            }
        }

        // otherwise fetch synchronously
        if (feed == null) {
            // attempt to fetch from relay peer
            feed = pullFromRelay(request);
        }

        if (feed != null) {
            // ensure it's a naked feed:
            // entries matching query params will get added later
            for (Entry e : feed.getEntries()) {
                e.discard();
            }

            // store in request context
            wrapper.setAttribute(Scope.REQUEST, "com.trsst.Feed", feed);
            return feed;
        }

        throw new FileNotFoundException("Not found: " + feedId);
    }

    private static Feed fetchFeedFromStorage(String feedId, Storage storage) {
        Feed feed = null;
        try {
            log.debug("fetchFeedFromStorage: " + feedId);
            feed = (Feed) Abdera.getInstance().getParser()
                    .parse(new StringReader(storage.readFeed(feedId)))
                    .getRoot();
        } catch (FileNotFoundException fnfe) {
            log.debug("Not found in local storage: " + feedId);
        } catch (ParseException e) {
            log.debug("Could not parse feed from local storage: " + feedId, e);
        } catch (IOException e) {
            log.debug("Unexpected error reading from local storage: " + feedId,
                    e);
        }
        return feed;
    }

    /**
     * Called to trigger an asynchronous fetch, usually after we have returned
     * possibly stale data and we want to make sure it's refreshed on the next
     * pull. This implementation uses a short fuse timer task queue, but others
     * should implement a heuristic to queue this task for later based on the
     * likelihood that a refetch is needed, e.g. factoring in time since last
     * update and frequency of updates, etc.
     */
    protected void pullLaterFromRelay(final String feedId,
            final RequestContext request) {
        if (TASK_QUEUE == null) {
            TASK_QUEUE = new Timer();
        }
        final String uri = request.getResolvedUri().toString();
        log.debug("fetchLaterFromRelay: queuing: " + uri);
        if (!COALESCING_TIMERS.containsKey(uri)) {
            log.debug("fetchLaterFromRelay: creating: " + uri);
            TimerTask task = new TimerTask() {
                public void run() {
                    log.debug("fetchLaterFromRelay: starting: " + uri);
                    pullFromRelay(request);
                    COALESCING_TIMERS.remove(uri);
                }
            };
            COALESCING_TIMERS.put(uri, task);
            TASK_QUEUE.schedule(task, 6000); // six seconds
        }
    }

    public static Timer TASK_QUEUE;
    private static Map<String, TimerTask> COALESCING_TIMERS = new Hashtable<String, TimerTask>();

    private Feed pullFromRelay(RequestContext request) {
        Feed result = null;
        RequestContextWrapper wrapper = new RequestContextWrapper(request);
        int limit = 5; // arbitrary
        try {
            if (wrapper.getParameter("relayLimit") != null) {
                limit = Integer.parseInt(wrapper.getParameter("relayLimit"));
                if (limit > 10) {
                    log.warn("Arbitrarily capping specified limit to 10: "
                            + limit);
                    limit = 10; // arbitrary
                }
            }
        } catch (Throwable t) {
            log.warn("Could not parse relayLimit; defaulting to: " + limit);
        }

        // if relay peer count is less than search limit
        List<String> relays = wrapper.getParameters("relay");
        URL relayPeer = null;
        if (relays == null || relays.size() <= limit) {
            relayPeer = getRelayPeer(relays);
            if (relayPeer != null) {
                log.debug("Using relay peer: " + relayPeer);
                result = pullFromServiceUrl(request, relayPeer);
            } else {
                log.debug("No relay peer available for request: "
                        + request.getResolvedUri());
            }
        }

        if (result == null) {
            if (Common.isExternalId(feedId)) {
                // attempt to fetch directly
                log.debug("Fetching direct: " + feedId);
                result = fetchFromExternalSource(feedId);
            }
        }

        // if we got a result
        if (result != null) {
            ingestFromRelay(persistence, result, relayPeer, relays);
        }

        return result;
    }

    protected void ingestFromRelay(Storage storage, Feed feed, URL relayPeer,
            List<String> relays) {
        try {
            String feedIdentifier = Common.fromFeedUrn(feed.getId());
            if (Common.isExternalId(feedIdentifier)) {
                // convert from rss if needed
                if (feed.getClass().getName().indexOf("RssFeed") != -1) {
                    feed = convertFromRSS(feedIdentifier, feed);
                }
                if (feed != null) {
                    // process and persist external feed
                    ingestExternalFeed(feedIdentifier, feed, 25);
                }
            } else if (Common.isAggregateId(feedIdentifier)
                    && relayPeer != null) {
                // if it's an aggregate feed fetched from a relay
                ingestAggregateFeed(storage, feed, relayPeer, relays);
            } else {
                // ingest the native feed
                ingestFeed(storage, feed);
            }
        } catch (Throwable t) {
            log.error("Could not ingest feed: " + feed.getId(), t);
        }
    }

    /**
     * Returns a relay peer to use to fetch contents. Implementors should return
     * a url chosen from an evenly or randomly distributed mix of known trsst
     * servers based on the home urls of this servers hosted content. This
     * implementation currently returns a relay from the
     * com.trsst.client.storage property, or null if the property does not
     * exist.
     * 
     * @param relays
     *            may not return any relay on this list
     */
    protected URL getRelayPeer(List<String> excludeHashes) {
        if (RELAYS == null) {
            String property = System.getProperty("com.trsst.server.relays");
            if (property == null) {
                RELAYS = new String[0];
            } else {
                RELAYS = property.split(",");
            }
        }
        // return a random relay that's not on the exclude list
        Set<String> excludes = new HashSet<String>();
        if (excludeHashes != null) {
            excludes.addAll(excludeHashes);
        }
        List<String> relays = new LinkedList<String>();
        for (String relay : RELAYS) {
            relays.add(relay);
        }
        Collections.shuffle(relays);
        for (String relay : relays) {
            try {
                if (!excludes.contains(relay)) {
                    return new URL(relay);
                }
            } catch (MalformedURLException e) {
                log.error("getRelayPeer: bad relay specified: " + relay, e);
            }
        }
        return null;
    }

    /**
     * Return a one-way hash token for the specified relay url.
     */
    protected String getLocalRelayId() {
        if (RELAY_ID == null) {
            // shared across all instances
            try {
                RELAY_ID = Integer.toHexString(InetAddress.getLocalHost()
                        .hashCode());
            } catch (UnknownHostException e) {
                log.error("Could not obtain local IP address: falling back to loopback address");
                RELAY_ID = Integer.toHexString(InetAddress.getLoopbackAddress()
                        .hashCode());
            }
        }
        // FLAG: hash name for a bit of extra obscurity
        // would this be overkill? #paranoid
        // byte[] hostBytes;
        // try {
        // hostBytes = hostName.getBytes("UTF-8");
        // hashName = Base64.encodeBase64String(Common.hash(hostBytes, 0,
        // hostBytes.length));
        // } catch (UnsupportedEncodingException e1) {
        // log.error("Should never happen", e1);
        // hashName = hostName;
        // }
        return RELAY_ID;
    }

    private static String RELAY_ID;
    private static String[] RELAYS;

    /**
     * Fetch from the specified trsst service url, validate it, ingest it, and
     * return the returned feed.
     */
    private Feed pullFromServiceUrl(RequestContext request, URL serviceUrl) {
        String feedIdentifier = request.getTarget().getParameter("collection");
        String uri = request.getResolvedUri().toString();
        String path = null;
        String query = null;
        if (feedIdentifier == null) {
            // global query
            int index = uri.indexOf('?');
            if (index != -1) {
                query = uri.substring(index + 1);
                path = "";
            } else {
                log.error("Could not find query in service request: "
                        + request.getResolvedUri());
                return null;
            }
        } else {
            // feed query
            int index = uri.indexOf(feedIdentifier);
            if (index != -1) {
                path = uri.substring(index);
                index = path.indexOf('?');
                if (index != -1) {
                    query = path.substring(index + 1);
                    path = path.substring(0, index);
                }
            } else {
                log.error("Could not find feed id in service request: "
                        + request.getResolvedUri());
                return null;
            }
        }
        return pullFromServiceUrl(serviceUrl, path, query);
    }

    /**
     * Fetch from the specified trsst service url, validate it, ingest it, and
     * return the returned feed.
     */
    private Feed pullFromServiceUrl(URL serviceUrl, String path,
            String queryString) {
        log.trace("pullFromServiceUrl: uri: " + serviceUrl.toString() + " : "
                + path + " : " + queryString);
        if (queryString == null) {
            queryString = "";
        }
        if (queryString.indexOf("relay=" + getLocalRelayId()) != -1) {
            // if we're alerady in the list of relay peers
            log.error("Unexpected relay loopback: ignoring request");
            return null;
        }
        if (queryString.length() > 0) {
            queryString = queryString + '&';
        }
        // add self as relay
        queryString = queryString + "relay=" + getLocalRelayId();

        return pullFromService(serviceUrl.toString(), path, queryString);
    }

    /**
     * For external feed ids: fetch directly from external source, convert to a
     * trsst feed, (optionally validate it), (optionally persist it), and return
     * the feed.
     */
    protected Feed fetchFromExternalSource(String feedId) {
        Feed result = null;
        try {
            AbderaClient client = new AbderaClient(Abdera.getInstance(),
                    Common.getBuildString());
            new URL(feedId); // validates as a url
            ClientResponse response = client.get(feedId);
            if (response.getType() == ResponseType.SUCCESS) {
                Document<Feed> document = response.getDocument();
                if (document != null) {
                    return document.getRoot();
                } else {
                    log.warn("fetchFromExternalSource: no document for: "
                            + feedId);
                }
            } else {
                log.debug("fetchFromExternalSource: no document found for: "
                        + feedId + " : " + response.getType());
            }
        } catch (MalformedURLException urle) {
            log.error("Not a valid external feed id: " + feedId);
        } catch (ClassCastException cce) {
            log.error("Not a valid feed: " + feedId, cce);
        } catch (Exception e) {
            log.error("Could not process external feed: " + feedId, e);
        }
        return result;
    }

    @Override
    public String getId(RequestContext request) {
        return feedId;
    }

    @Override
    public String getAuthor(RequestContext request)
            throws ResponseContextException {
        Person author = null;
        try {
            author = currentFeed(request).getAuthor();
        } catch (FileNotFoundException e) {
            log.trace("Could not find feed: " + feedId, e);
        } catch (ParseException e) {
            log.error("Could not parse stored feed: " + feedId, e);
        } catch (IOException e) {
            log.error("Unexpected error reading feed: " + feedId, e);
        }
        if (author != null) {
            return author.getName();
        }
        return null;
    }

    @Override
    protected Feed createFeedBase(RequestContext request)
            throws ResponseContextException {
        try {
            return (Feed) currentFeed(request).clone();
        } catch (FileNotFoundException e) {
            log.debug("Could not find feed: " + feedId, e);
        } catch (ParseException e) {
            log.error("Could not parse stored feed: " + feedId, e);
        } catch (IOException e) {
            log.error("Unexpected error reading feed: " + feedId, e);
        }
        return null;
    }

    public String getTitle(RequestContext request) {
        try {
            return currentFeed(request).getTitle();
        } catch (FileNotFoundException e) {
            log.debug("Could not find feed: " + feedId, e);
        } catch (ParseException e) {
            log.error("Could not parse stored feed: " + feedId, e);
        } catch (IOException e) {
            log.error("Unexpected error reading feed: " + feedId, e);
        }
        return null;
    }

    public String getHref(RequestContext request) {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("collection", getId(request));
        return request.urlFor(TargetType.TYPE_COLLECTION, params);
    }

    /**
     * Returns a feed document containing all the entries for this feed, subject
     * to pagination.
     */
    public ResponseContext getFeed(RequestContext request) {
        try {
            Feed result = currentFeed(request);
            fetchEntriesFromStorage(request, result);
            return ProviderHelper.returnBase(result, 200, result.getUpdated());
            // FIXME: Abdera's etag creator doesn't create valid etags
            // .setEntityTag(ProviderHelper.calculateEntityTag(result));
        } catch (IllegalArgumentException e) {
            log.debug("Bad request: " + feedId, e);
            return ProviderHelper.badrequest(request, e.getMessage());
        } catch (FileNotFoundException e) {
            log.debug("Could not find feed: " + feedId, e);
            return ProviderHelper.notfound(request);
        } catch (ParseException e) {
            log.error("Could not parse stored feed: " + feedId, e);
            return ProviderHelper.servererror(request, e);
        } catch (IOException e) {
            log.error("Unexpected error reading feed: " + feedId, e);
            return ProviderHelper.servererror(request, e);
        }
    }

    /**
     * Returns a feed document containing the single requested entry. NOTE: this
     * is a deviation from atompub. TODO: not much point in returning feed now;
     * prolly should conform to spec.
     */
    public ResponseContext getEntry(RequestContext request) {
        // make a copy of the current template
        Feed result;
        try {
            result = currentFeed(request);
            // add requested entry
            String entryId = request.getTarget().getParameter("entry");
            Document<Entry> entry = getEntry(persistence, feedId,
                    Common.toEntryId(entryId));
            if (entry != null) {
                result.addEntry(entry.getRoot());
            } else {
                return ProviderHelper.notfound(request);
            }
            return ProviderHelper.returnBase(result, 200, result.getUpdated());
            // FIXME: Abdera's etag creator doesn't create valid etags
            // .setEntityTag(ProviderHelper.calculateEntityTag(result));
        } catch (FileNotFoundException e) {
            log.debug("Could not find feed: " + feedId, e);
            return ProviderHelper.notfound(request);
        } catch (ParseException e) {
            log.error("Could not parse stored feed: " + feedId, e);
            return ProviderHelper.servererror(request, e);
        } catch (IOException e) {
            log.error("Unexpected error reading feed: " + feedId, e);
            return ProviderHelper.servererror(request, e);
        }
    }

    protected static Document<Entry> getEntry(Storage storage, String feedId,
            long entryId) {
        try {
            // NOTE: by this point currentFeed() will have fetched
            // the requested entry via relay if needed
            // FIXME: this is not currently working; need a test case

            // fetch from local storage
            return Abdera
                    .getInstance()
                    .getParser()
                    .parse(new StringReader(storage.readEntry(feedId, entryId)));
        } catch (FileNotFoundException fnfe) {
            // fall through
        } catch (Exception e) {
            log.error("Unexpected error: " + feedId + " : " + entryId, e);
        }
        return null;
    }

    /**
     * Accepts a signed feed document containing one or more signed entries and
     * attachments for some or all of those entries.
     */
    public ResponseContext postMedia(RequestContext request) {
        try {
            if (MimeTypeHelper.isMultipart(request.getContentType().toString())) {
                byte[] requestData = Common.readFully(request.getInputStream());
                List<MultipartRelatedPost> posts = getMultipartRelatedData(
                        request, new ByteArrayInputStream(requestData));
                Feed incomingFeed = null;
                if (posts != null) {
                    Map<String, Entry> contentIdToEntry = new HashMap<String, Entry>();
                    Map<String, String> contentIdToType = new HashMap<String, String>();
                    Map<String, InputStream> contentIdToData = new HashMap<String, InputStream>();
                    for (MultipartRelatedPost post : posts) {
                        String type = post.getDataHeaders().get("content-type");
                        String cid = post.getDataHeaders().get("content-id");
                        if (cid != null) {
                            if (cid.startsWith("<cid:")) {
                                cid = cid.substring(5);
                                cid = cid.substring(0, cid.length() - 1);
                            }
                            // find content id in entry list
                            List<Entry> entries;
                            if (post.getSource().getRoot() instanceof Feed) {
                                incomingFeed = ((Feed) post.getSource()
                                        .getRoot());
                                entries = incomingFeed.getEntries();
                            } else if (post.getSource().getRoot() instanceof Entry) {
                                log.warn("Single entries not supported: "
                                        + post.getSource().getRoot());
                                entries = new LinkedList<Entry>();
                                entries.add((Entry) post.getSource().getRoot());
                                return ProviderHelper
                                        .badrequest(request,
                                                "Single entries not currently supported.");
                            } else {
                                log.error("Unrecognized source: "
                                        + post.getSource());
                                return ProviderHelper.badrequest(request,
                                        "Unrecognized source.");
                            }
                            for (Entry entry : entries) {
                                if (entry.getContentSrc() != null
                                        && entry.getContentSrc().toString()
                                                .endsWith(cid)) {
                                    // getContentSrc resolves against baseurl
                                    contentIdToEntry.put(cid, entry);
                                    contentIdToType.put(cid, type);
                                    contentIdToData.put(cid, post.getData());
                                }
                            }
                        }
                    }
                    // if all content ids match an entry content element
                    if (contentIdToEntry.size() == posts.size()) {
                        ingestFeed(persistence, incomingFeed);
                        for (Map.Entry<String, Entry> i : contentIdToEntry
                                .entrySet()) {
                            String cid = i.getKey();
                            Entry entry = i.getValue();

                            // TODO: grab from attribute instead
                            // String algorithm = "ripemd160";
                            String hash = cid;
                            int dot = hash.indexOf('.');
                            if (dot != -1) {
                                // remove any mimetype hint
                                // (some feed readers like to see
                                // a file extension on enclosures)
                                hash = hash.substring(0, dot);
                            }
                            byte[] data = Common.readFully(contentIdToData
                                    .get(cid));
                            String digest = new Base64(0, null, true)
                                    .encodeToString(Common.ripemd160(data));
                            if (digest.equals(hash)) {
                                // only store if hash matches content id
                                persistence.updateFeedEntryResource(feedId,
                                        Common.toEntryId(entry.getId()), cid,
                                        contentIdToType.get(cid),
                                        entry.getPublished(), data);
                            } else {
                                log.error("Content digests did not match: "
                                        + hash + " : " + digest);
                                return ProviderHelper.badrequest(request,
                                        "Could not verify content digest for: "
                                                + hash);
                            }
                        }
                        pushRawPostIfNeeded(incomingFeed, request, requestData);
                        return ProviderHelper.returnBase(incomingFeed, 201,
                                null);
                    }
                }
            }
        } catch (Exception pe) {
            log.error("postMedia: ", pe);
            return ProviderHelper.badrequest(request,
                    "Could not process multipart request: " + pe.getMessage());
        }
        return ProviderHelper.badrequest(request,
                "Could not process multipart request");
    }

    /**
     * Validate then persist incoming feed and entries. Any exception thrown
     * means no feed or entries are persisted.
     * 
     * @param feed
     *            with zero or more entries to be validated and persisted.
     * @throws XMLSignatureException
     *             if signature verification fails
     * @throws IllegalArgumentException
     *             if data validation fails
     * @throws Exception
     *             any other problem
     */
    protected static void ingestFeed(Storage storage, Feed feed)
            throws XMLSignatureException, IllegalArgumentException, Exception {

        // clone a copy so we can manipulate
        feed = (Feed) feed.clone();

        // validate feed
        Date lastUpdated = feed.getUpdated();
        if (lastUpdated == null) {
            throw new IllegalArgumentException(
                    "Feed update timestamp is required: " + feed.getId());
        }
        if (lastUpdated.after(new Date(
                System.currentTimeMillis() + 1000 * 60 * 5))) {
            // allows five minutes of variance
            throw new IllegalArgumentException(
                    "Feed update timestamp cannot be in the future: "
                            + feed.getId());
        }

        // grab the signing key
        Element signingElement = feed.getFirstChild(new QName(Common.NS_URI,
                Common.SIGN));
        if (signingElement == null) {
            throw new XMLSignatureException(
                    "Could not find signing key for feed: " + feed.getId());
        }

        // verify that the key matches the id
        PublicKey publicKey = Common.toPublicKeyFromX509(signingElement
                .getText());
        if (Common.fromFeedUrn(feed.getId()) == null
                || !Common.fromFeedUrn(feed.getId()).equals(
                        Common.toFeedId(publicKey))) {
            throw new XMLSignatureException(
                    "Signing key does not match feed id: "
                            + Common.fromFeedUrn(feed.getId()) + " : "
                            + Common.toFeedId(publicKey));
        }

        // prep the verifier
        AbderaSecurity security = new AbderaSecurity(Abdera.getInstance());
        Signature signature = security.getSignature();
        SignatureOptions options = signature.getDefaultSignatureOptions();
        options.setSigningAlgorithm("http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha1");
        options.setSignLinks(false);
        options.setPublicKey(publicKey);

        // validate, persist, and remove each entry
        List<Entry> entries = new LinkedList<Entry>();
        entries.addAll(feed.getEntries()); // make a copy
        for (Entry entry : feed.getEntries()) {
            if (!signature.verify(entry, options)) {
                // failed validation
                Element activity = entry
                        .getExtension(new QName(
                                "http://activitystrea.ms/spec/1.0/", "verb",
                                "activity"));
                // if not a 'deleted' entry
                if (activity == null || !"deleted".equals(activity.getText())) {
                    // TODO: should validate that the 'delete' entry that
                    // this entry mentions is mentioning this entry
                    log.warn("Could not verify signature for entry with id: "
                            + feed.getId());
                    // fail ingest
                    throw new XMLSignatureException(
                            "Could not verify signature for entry with id: "
                                    + entry.getId() + " : " + feed.getId());
                } else {
                    log.warn("Skipping signature verification for deleted entry: "
                            + feed.getId());
                }
            }
            // remove from feed parent
            entry.discard();
            try {
                // see if this file already exists
                storage.readEntry(Common.toFeedIdString(feed.getId()),
                        Common.toEntryId(entry.getId()));
                // this file exists; remove from processing
                entries.remove(entry);
            } catch (FileNotFoundException e) {
                // file does not already exist: resume
            }
        }
        // setEditDetail(request, entry, key);
        // String edit = entry.getEditLinkResolvedHref().toString();

        // remove all navigation links before signing
        for (Link link : feed.getLinks()) {
            if (Link.REL_FIRST.equals(link.getRel())
                    || Link.REL_LAST.equals(link.getRel())
                    || Link.REL_CURRENT.equals(link.getRel())
                    || Link.REL_NEXT.equals(link.getRel())
                    || Link.REL_PREVIOUS.equals(link.getRel())) {
                link.discard();
            }
        }
        // remove all opensearch elements before verifying
        for (Element e : feed
                .getExtensions("http://a9.com/-/spec/opensearch/1.1/")) {
            e.discard();
        }

        // now validate feed signature sans entries
        if (!signature.verify(feed, options)) {
            log.warn("Could not verify signature for feed with id: "
                    + feed.getId());
            throw new XMLSignatureException(
                    "Could not verify signature for feed with id: "
                            + feed.getId());
        }

        // persist feed
        storage.updateFeed(Common.toFeedIdString(feed.getId()),
                feed.getUpdated(), feed.toString());
        // only now persist each entry
        for (Entry entry : entries) {
            Date date = entry.getPublished();
            if (date == null) {
                // fall back to updated if publish not set
                date = entry.getUpdated();
            }
            storage.updateEntry(Common.toFeedIdString(feed.getId()),
                    Common.toEntryId(entry.getId()), date, entry.toString());

            // check for delete operation
            String verb = entry.getSimpleExtension(new QName(
                    "http://activitystrea.ms/spec/1.0/", "verb", "activity"));
            if ("delete".equals(verb)) {
                // get mentions
                List<Category> mentions = entry.getCategories();
                for (Category mention : mentions) {
                    IRI scheme = mention.getScheme();
                    if (scheme != null
                            && Common.MENTION_URN.equals(scheme.toString())) {
                        Entry deleted = null;
                        try {
                            deleted = deleteEntry(storage,
                                    Common.toFeedIdString(feed.getId()),
                                    Common.toEntryId(mention.getTerm()),
                                    Common.toEntryId(entry.getId()));
                        } catch (IOException exc) {
                            log.error(
                                    "Could not delete entry: " + entry.getId(),
                                    exc);
                        }
                        if (deleted != null) {
                            log.debug("Deleted entry: " + entry.getId());
                        } else {
                            log.error("Failed to delete entry: "
                                    + entry.getId());
                        }
                    }
                }
            }
        }
    }

    /**
     * Convert external feed and entries and persist. Any exception thrown means
     * no feed or entries are persisted.
     * 
     * External feeds are existing RSS and Atom feeds that are ingested by a
     * trsst server on behalf of a user request and converted into unsigned
     * trsst feeds and entries.
     * 
     * Note that unsigned or external feeds are never pushed to a trsst server:
     * they are only ever fetched on behalf of a request from a client. Trsst
     * servers never accept a push of unsigned feeds or entries.
     * 
     * @param feed
     *            with zero or more entries to be validated and persisted.
     * @throws XMLSignatureException
     *             if signature verification fails
     * @throws IllegalArgumentException
     *             if data validation fails
     * @throws Exception
     *             any other problem
     */
    protected void ingestExternalFeed(String feedId, Feed feed, int limit)
            throws XMLSignatureException, IllegalArgumentException, Exception {

        // clone a copy so we can manipulate
        feed = (Feed) feed.clone();

        // for our purposes: replace the existing feed id with the URL
        feed.setId(Common.toFeedUrn(feedId));

        // validate, persist, and remove each entry
        List<Entry> entries = new LinkedList<Entry>();
        entries.addAll(feed.getEntries()); // make a copy

        // restrict to limit count
        entries = entries.subList(0, Math.min(limit, entries.size()));

        int count = 0;
        for (Entry entry : feed.getEntries()) {

            if (count++ < limit) {
                // convert existing entry id to a trsst timestamp-based id
                String existing = entry.getId().toString();
                long timestamp = entry.getUpdated().getTime();

                // RSS feeds don't have millisecond precision
                // so we need to add it to avoid duplicate ids
                if (timestamp % 1000 == 0) {
                    // need a deterministic source
                    String hash = existing.toString() + entry.getTitle();
                    timestamp = timestamp + hash.hashCode() % 1000;
                }

                try {
                    // see if this file already exists
                    persistence.readEntry(feedId, timestamp);
                    // this file exists; remove from processing
                    entries.remove(entry);
                } catch (FileNotFoundException e) {
                    // we don't already have it:
                    // if it's not in trsst id format
                    if (!existing.startsWith(Common.ENTRY_URN_PREFIX)) {
                        // construct a trsst id for this entry
                        entry.setId(Common.toEntryUrn(feedId, timestamp));
                    }
                }
            }
            // remove from feed parent
            entry.discard();
        }

        if (entries.isEmpty()) {
            // no new entries to update;
            // prevent the update of this feed
            return;
        }

        // remove all navigation links before persisting
        for (Link link : feed.getLinks()) {
            if (Link.REL_FIRST.equals(link.getRel())
                    || Link.REL_LAST.equals(link.getRel())
                    || Link.REL_CURRENT.equals(link.getRel())
                    || Link.REL_NEXT.equals(link.getRel())
                    || Link.REL_PREVIOUS.equals(link.getRel())) {
                link.discard();
            }
        }
        // remove all opensearch elements before verifying
        for (Element e : feed
                .getExtensions("http://a9.com/-/spec/opensearch/1.1/")) {
            e.discard();
        }

        // persist feed
        persistence.updateFeed(feedId, feed.getUpdated(), feed.toString());
        // only now persist each entry
        for (Entry entry : entries) {
            Date date = entry.getPublished();
            if (date == null) {
                // fall back to updated if publish not set
                date = entry.getUpdated();
            }
            persistence.updateEntry(feedId, Common.toEntryId(entry.getId()),
                    date, entry.toString());
        }
    }

    /**
     * Aggregate feeds contain signed entries from a number of feeds. To ingest,
     * we refetch each entry directly with its feed.
     */
    protected void ingestAggregateFeed(Storage storage, Feed feed,
            URL relayUrl, List<String> relays) {
        Object id;
        String feedIdentifier;
        String entryIdentifier;
        // for each entry
        for (Entry entry : feed.getEntries()) {
            id = entry.getId();
            feedIdentifier = Common.toFeedIdString(id);
            entryIdentifier = Common.toEntryIdString(id);
            try {
                // see if this file already exists locally
                persistence.readEntry(feedIdentifier,
                        Common.toEntryId(entryIdentifier));
                log.info("Entry found: skipping: " + id);
            } catch (FileNotFoundException e) {
                log.info("Entry not found: fetching: " + id);
                // we don't already have it:
                String queryString = null;
                if (relays != null) {
                    // reconstruct the relays parameter
                    queryString = "";
                    for (String relay : relays) {
                        queryString = queryString + "relay=" + relay + '&';
                    }
                    queryString = queryString.substring(0,
                            queryString.length() - 1);
                }
                // fetch enclosing feed
                Feed result = pullFromServiceUrl(relayUrl, feedIdentifier + '/'
                        + entryIdentifier, queryString);
                // and ingest
                if (result != null) {
                    ingestFromRelay(storage, result, relayUrl, relays);
                }
            } catch (IOException ioe) {
                log.error("Unexpected exception from readEntry", ioe);
            }
        }
    }

    /**
     * Converts from RSS parser's read-only Feed to a mutable Feed.
     */
    protected Feed convertFromRSS(String feedId, Feed feed) {
        Feed result = Abdera.getInstance().newFeed();

        // for our purposes: replace the existing feed id with the URL
        result.setId(Common.toFeedUrn(feedId));

        Date mostRecent = null;
        result.setBaseUri(feed.getBaseUri());
        result.setUpdated(feed.getUpdated());
        if (feed.getIcon() != null) {
            result.setIcon(feed.getIcon().toString());
        }
        if (feed.getLogo() != null) {
            result.setLogo(feed.getLogo().toString());
        }
        result.setTitle(feed.getTitle());
        result.setSubtitle(feed.getSubtitle());
        if (feed.getAuthor() != null) {
            Person existingAuthor = feed.getAuthor();
            Person author = Abdera.getInstance().getFactory().newAuthor();
            author.setName(existingAuthor.getName());
            author.setEmail(existingAuthor.getEmail());
            if (existingAuthor.getUri() != null) {
                author.setUri(existingAuthor.getUri().toString());
            }
            result.addAuthor(author);
        }
        // for (Category category : feed.getCategories()) {
        // result.addCategory(category.getTerm());
        // java.lang.ClassCastException:
        // org.apache.abdera.parser.stax.FOMExtensibleElement cannot be cast to
        // org.apache.abdera.model.Category
        // }
        for (Link link : feed.getLinks()) {
            result.addLink(link);
        }

        for (Entry entry : feed.getEntries()) {
            try {

                // convert existing entry id to a trsst timestamp-based id
                Entry converted = Abdera.getInstance().newEntry();
                Date updated = entry.getUpdated();
                if (updated == null) {
                    updated = entry.getPublished();
                    if (updated == null) {
                        // fall back on dc:date
                        Element dcDate = entry.getExtension(new QName(
                                "http://purl.org/dc/elements/1.1/", "date"));
                        try {
                            updated = new AtomDate(dcDate.getText()).getDate();
                        } catch (Throwable t) {
                            log.warn("Could not parse date for feed: " + dcDate
                                    + " : " + feed.getId());
                        }
                    }
                }
                long timestamp = updated.getTime();
                if (mostRecent == null || mostRecent.before(updated)) {
                    mostRecent = updated;
                }
                Object existing = null;
                try {
                    existing = entry.getId();
                } catch (IRISyntaxException irie) {
                    // EFF's entry ids have spaces
                    // "<guid isPermaLink="false">78822 at https://www.eff.org</guid>"
                }
                if (existing == null) {
                    existing = updated;
                }

                // RSS feeds don't have millisecond precision
                // so we need to add it to avoid duplicate ids
                if (timestamp % 1000 == 0) {
                    // need a deterministic source
                    String hash = existing.toString() + entry.getTitle();
                    timestamp = timestamp + hash.hashCode() % 1000;
                }
                converted.setId(Common.toEntryUrn(feedId, timestamp));
                converted.setUpdated(new Date(timestamp));
                converted.setPublished(entry.getPublished());
                converted.setTitle(entry.getTitle());

                // find "link"
                String linkSrc = null;
                if (entry.getExtension(RssConstants.QNAME_LINK) != null) {
                    Element existingLink = entry
                            .getExtension(RssConstants.QNAME_LINK);
                    linkSrc = existingLink.getText();
                    Link link = Abdera.getInstance().getFactory().newLink();
                    link.setAttributeValue("src", linkSrc);
                    link.setRel("alternate");
                    link.setMimeType("text/html");
                    converted.addLink(link);
                }

                // convert content
                Content existingContent = entry.getContentElement();
                if (existingContent != null) {
                    Content convertedContent = Abdera.getInstance()
                            .getFactory().newContent();
                    List<QName> attributes = existingContent.getAttributes();
                    for (QName attribute : attributes) {
                        convertedContent.setAttributeValue(attribute,
                                existingContent.getAttributeValue(attribute));
                    }
                    converted.setContentElement(convertedContent);
                } else if (entry.getExtension(RssConstants.QNAME_ENCLOSURE) != null) {
                    Element enclosure = entry
                            .getExtension(RssConstants.QNAME_ENCLOSURE);
                    Content convertedContent = Abdera.getInstance()
                            .getFactory().newContent();
                    convertedContent.setAttributeValue("src",
                            enclosure.getAttributeValue("url"));
                    convertedContent.setAttributeValue("type",
                            enclosure.getAttributeValue("type"));
                    convertedContent.setAttributeValue("length",
                            enclosure.getAttributeValue("length"));
                    converted.setContentElement(convertedContent);
                    Link link = Abdera.getInstance().getFactory().newLink();
                    link.setAttributeValue("src",
                            enclosure.getAttributeValue("url"));
                    link.setAttributeValue("type",
                            enclosure.getAttributeValue("type"));
                    link.setAttributeValue("length",
                            enclosure.getAttributeValue("length"));
                    link.setRel("enclosure");
                    converted.addLink(link);
                } else if (linkSrc != null) {
                    Content convertedContent = Abdera.getInstance()
                            .getFactory().newContent();
                    convertedContent.setAttributeValue("src", linkSrc);
                    convertedContent.setAttributeValue("type", "text/html");
                    converted.setContentElement(convertedContent);
                }

                if (entry.getAuthor() != null) {
                    Person existingAuthor = entry.getAuthor();
                    Person author = Abdera.getInstance().getFactory()
                            .newAuthor();
                    author.setName(existingAuthor.getName());
                    author.setEmail(existingAuthor.getEmail());
                    if (existingAuthor.getUri() != null) {
                        author.setUri(existingAuthor.getUri().toString());
                    }
                    converted.addAuthor(author);
                }
                for (Link link : entry.getLinks()) {
                    converted.addLink(link);
                }
                converted.setRights(entry.getRights());

                String summary = entry.getSummary();
                if (summary != null) {
                    if (Text.Type.HTML.equals(converted.getSummaryType())) {
                        converted
                                .setSummary(entry.getSummary(), Text.Type.HTML);
                    } else {
                        converted
                                .setSummary(entry.getSummary(), Text.Type.TEXT);
                    }
                }

                // remove from feed parent
                result.addEntry(converted);
            } catch (Throwable t) {
                log.warn("Could not convert RSS entry: " + entry.toString(), t);
            }
        }

        // workaround: some RSS feeds have no update timestamp
        // and that throws abdera for an NPE.
        Date updated = feed.getUpdated();
        if (updated == null) {
            log.debug("Ingesting RSS feed with no update timestamp: using most recent entry"
                    + feedId);
            updated = mostRecent;
        }
        if (updated == null) {
            log.debug("Ingesting RSS feed with no update timestamp: using last known time"
                    + feedId);
            Feed existingFeed = fetchFeedFromStorage(feedId, persistence);
            if (existingFeed != null) {
                updated = existingFeed.getUpdated();
            }
        }
        if (updated == null) {
            log.debug("Ingesting RSS feed with no update timestamp: using one day ago"
                    + feedId);
            updated = new Date(System.currentTimeMillis()
                    - (1000 * 60 * 60 * 24));
        }
        result.setUpdated(updated);

        return result;
    }

    /**
     * Accepts a signed feed document containing one or more signed entries. All
     * signatures must be valid or the entire transaction will be rejected.
     * NOTE: this is a deviation from atompub.
     */
    public ResponseContext postEntry(RequestContext request) {
        if (request.isAtom()) {
            try {
                // FIXME: using SSL, this line fails from erroneously loading a
                // UTF-32 reader
                // CharConversionException: Invalid UTF-32 character 0x6565663c
                // at char #0, byte #3)
                // at
                // com.ctc.wstx.io.UTF32Reader.reportInvalid(UTF32Reader.java:197)
                // Feed incomingFeed = (Feed) request.getDocument().getRoot();

                // WORKAROUND:
                // loading the stream and making our own parser works
                byte[] bytes = Common.readFully(request.getInputStream());
                // System.out.println(new String(bytes, "UTF-8"));
                Feed incomingFeed = (Feed) Abdera.getInstance().getParser()
                        .parse(new ByteArrayInputStream(bytes)).getRoot();

                // we require a feed entity (not solo entries like atompub)
                ingestFeed(persistence, incomingFeed);
                pushRawPostIfNeeded(incomingFeed, request, bytes);
                return ProviderHelper.returnBase(incomingFeed, 201, null);
            } catch (XMLSignatureException xmle) {
                log.error("Could not verify signature: ", xmle);
                return ProviderHelper.badrequest(request,
                        "Could not verify signature: " + xmle.getMessage());
            } catch (FileNotFoundException fnfe) {
                return ProviderHelper.notfound(request, "Not found: " + feedId);
            } catch (Exception e) {
                log.warn("Bad request: " + feedId, e);
                return ProviderHelper.badrequest(request, e.toString());
            }
        } else {
            return ProviderHelper.notsupported(request);
        }
    }

    /**
     * PUT operations are treated as POST operations. NOTE: this is a deviation
     * from atompub.
     */
    public ResponseContext putEntry(RequestContext request) {
        return postEntry(request);
    }

    /**
     * Replaces the mentioned entry with a new entry that retains only the
     * following elements: id, updated, published, predecessor, signature;
     * adding only the verb 'deleted' and a single mention of the 'delete'
     * entry.
     * 
     * @param deletedId
     *            the id to be deleted
     * @param deletingId
     *            the id to be mentioned
     */
    private static Entry deleteEntry(Storage storage, String feedId,
            long deletedId, long deletingId) throws IOException {
        Document<Entry> document = getEntry(storage, feedId, deletedId);
        Element element;
        if (document != null) {

            // copy with only minimum of elements
            Entry existing = document.getRoot();
            Entry replacement = Abdera.getInstance().newEntry();
            replacement.setId(existing.getId().toString());
            replacement.setUpdated(existing.getUpdated());
            replacement.setPublished(existing.getPublished());
            element = existing.getFirstChild(new QName(
                    "http://www.w3.org/2000/09/xmldsig#", "Signature"));
            replacement.addExtension(element);
            element = existing.getFirstChild(new QName(Common.NS_URI,
                    Common.PREDECESSOR));
            // might not have predecessor if genesis entry
            if (element != null) {
                replacement.addExtension(element);
            }

            // add verb 'deleted'
            replacement.addSimpleExtension(new QName(
                    "http://activitystrea.ms/spec/1.0/", "verb", "activity"),
                    "deleted");

            // add reference to deleting id
            replacement.addCategory(Common.MENTION_URN,
                    Common.toEntryUrn(feedId, deletingId), "Mention");

            // write the entry
            storage.updateEntry(feedId, deletedId, replacement.getUpdated(),
                    replacement.toString());
            return replacement;
        }
        return null;
    }

    /**
     * DELETE operations are not permitted.
     * 
     * Instead: post an entry with verb "delete" and mentioning one or more
     * entries. The act of deleting an entry in this way is a revocation by the
     * author of publication and distribution rights to the specified entry.
     * 
     * Trsst servers that receive "delete" entries must immediately replace
     * their stored copies of the mentioned entries with new entries that retain
     * only the following elements: id, updated, published, predecessor,
     * signature; adding only the verb 'deleted' and a single mention of the
     * 'delete' entry.
     * 
     * The signature will no longer validate, but is required for blockchain
     * integrity, and relays can verify the referenced "delete" entry to allow
     * redistribution of the deleted entry.
     */
    public ResponseContext deleteEntry(RequestContext request) {
        return ProviderHelper.notallowed(request);
    }

    public ResponseContext extensionRequest(RequestContext request) {
        return ProviderHelper.notallowed(request, "Method Not Allowed",
                ProviderHelper.getDefaultMethods(request));
    }

    /**
     * Categories map to feed ids available on this server. This might be only
     * the feeds belonging to a server's "registered users" or all feeds cached
     * by a server or some logical place inbetween.
     */
    public ResponseContext getCategories(RequestContext request) {
        return new StreamWriterResponseContext(request.getAbdera()) {
            protected void writeTo(StreamWriter sw) throws IOException {
                sw.startDocument().startCategories(false);
                for (String id : persistence.getFeedIds(0, 100)) {
                    sw.writeCategory(id);
                }
                sw.endCategories().endDocument();
            }
        }.setStatus(200).setContentType(Constants.CAT_MEDIA_TYPE);
    }

    @SuppressWarnings({ "rawtypes" })
    protected void fetchEntriesFromStorage(RequestContext context, Feed feed)
            throws FileNotFoundException, IOException {
        // NOTE: occasionally (<1%) jetty and/or abdera give us a servlet
        // request that has a valid query string but returns no parameters;
        // we now just parse the query manually every time just to be safe
        Hashtable params = new Hashtable();
        String uri = context.getUri().toString();
        int i = uri.indexOf('?');
        if (i != -1) {
            params = HttpUtils.parseQueryString(uri.substring(i + 1));
        }
        // System.out.println("fetchEntriesFromStorage: " + params + " : " +
        // uri);

        String searchTerms = params.get("q") == null ? null
                : ((String[]) params.get("q"))[0];
        Date beginDate = null;

        String verb = params.get("verb") == null ? null : ((String[]) params
                .get("verb"))[0];

        String[] mentions = (String[]) params.get("mention");
        String[] tags = (String[]) params.get("tag");
        String after = params.get("after") == null ? null : ((String[]) params
                .get("after"))[0];
        if (after != null) {
            try {
                // try to parse an entry id timestamp
                beginDate = new Date(Long.parseLong(after, 16));
            } catch (NumberFormatException nfe) {
                // try to parse as ISO date
                String begin = after;
                String beginTemplate = "0000-01-01T00:00:00.000Z";
                if (begin.length() < beginTemplate.length()) {
                    begin = begin + beginTemplate.substring(begin.length());
                }
                try {
                    beginDate = new AtomDate(begin).getDate();
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            "Could not parse begin date: " + begin);
                }
            }

        }
        Date endDate = null;
        String before = params.get("before") == null ? null
                : ((String[]) params.get("before"))[0];
        if (before != null) {
            try {
                // try to parse an entry id timestamp
                endDate = new Date(Long.parseLong(before, 16));
            } catch (NumberFormatException nfe) {
                // try to parse as ISO date
                String end = before;
                String endTemplate = "9999-12-31T23:59:59.999Z";
                if (end.length() < endTemplate.length()) {
                    end = end + endTemplate.substring(end.length());
                }
                try {
                    endDate = new AtomDate(end).getDate();
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            "Could not parse end date: " + end);
                }
            }
        }

        // note: "default" to getPageSize was actually max page size
        int length = ProviderHelper.getPageSize(context, "count", 99);
        String _count = params.get("count") == null ? null : ((String[]) params
                .get("count"))[0];
        if (_count == null) {
            if (context.getUri().toString().indexOf("count=") != -1) {
                // BUG in abdera?
                log.error("Abdera returning no count from valid count parameter: "
                        + context.getUri());
            }
        }
        // int offset = ProviderHelper.getOffset(context, "page", length);
        String _page = params.get("page") == null ? null : ((String[]) params
                .get("page"))[0];
        int page = (_page != null) ? Integer.parseInt(_page) : 0;
        int begin = page * length;
        int total = 0;
        if (length > 0) {
            total = addEntriesFromStorage(feed, begin, length, beginDate,
                    endDate, searchTerms, mentions, tags, verb);
        } else {
            total = countEntriesFromStorage(beginDate, endDate, searchTerms,
                    mentions, tags, verb);
        }
        addPagingLinks(context, feed, page, length, total, searchTerms, before,
                after, mentions, tags, verb);
    }

    /**
     * Adds entries to the specified feed for the specified search and paging
     * parameters.
     * 
     * @return the total number of entries matching the query.
     */
    protected int addEntriesFromStorage(Feed feed, int start, int length,
            Date after, Date before, String query, String[] mentions,
            String[] tags, String verb) {
        long[] entryIds = persistence.getEntryIdsForFeedId(feedId, 0, length,
                after, before, query, mentions, tags, verb);
        int end = Math.min(entryIds.length, start + length);
        Document<Entry> document;
        for (int i = start; i < end; i++) {
            document = getEntry(persistence, feedId, entryIds[i]);
            if (document != null) {
                feed.addEntry((Entry) document.getRoot().clone());
            } else {
                log.error("Could not find entry for id: " + feedId + " : "
                        + Long.toHexString(entryIds[i]));
            }
        }
        return entryIds.length;
    }

    /**
     * Counts entries specified search parameters.
     * 
     * @return the total number of entries matching the query.
     */
    protected int countEntriesFromStorage(Date after, Date before,
            String query, String[] mentions, String[] tags, String verb) {
        return persistence.getEntryCountForFeedId(feedId, after, before, query,
                mentions, tags, verb);
    }

    private void addPagingLinks(RequestContext request, Feed feed,
            int currentPage, int itemsPerPage, int totalCount,
            String searchTerms, String before, String after, String[] mentions,
            String[] tags, String verb) {
        Map<String, Object> params = new HashMap<String, Object>();
        if (searchTerms != null) {
            params.put("q", searchTerms);
        }
        if (before != null) {
            params.put("before", before);
        }
        if (after != null) {
            params.put("after", after);
        }
        if (mentions != null) {
            // hack: template doesn't support duplicate keys with different
            // values
            String value = mentions[0];
            for (int i = 1; i < mentions.length; i++) {
                value = value + "&mention=" + mentions[i];
            }
            params.put("mention", value);
            // FIXME: this doesn't even work because string gets escaped
        }
        if (tags != null) {
            // hack: template doesn't support duplicate keys with different
            // values
            String value = tags[0];
            for (int i = 1; i < tags.length; i++) {
                value = value + "&tag=" + tags[i];
            }
            params.put("tag", value);
            // FIXME: this doesn't even work because string gets escaped
        }
        params.put("collection", request.getTarget().getParameter("collection"));
        params.put("count", itemsPerPage);
        params.put("page", currentPage);

        String current = paging_template.expand(params);
        // current = request.getResolvedUri().resolve(current).toString();
        feed.addLink(current, "current");
        if (totalCount > (currentPage + 1) * itemsPerPage) {
            params.put("page", currentPage + 1);
            String next = paging_template.expand(params);
            // next = request.getResolvedUri().resolve(next).toString();
            feed.addLink(next, "next");
        }
        if (currentPage > 0) {
            params.put("page", currentPage - 1);
            String prev = paging_template.expand(params);
            // prev = request.getResolvedUri().resolve(prev).toString();
            feed.addLink(prev, "previous");
        }

        // add opensearch tags
        feed.addSimpleExtension(new QName(
                "http://a9.com/-/spec/opensearch/1.1/", "totalResults",
                "opensearch"), Integer.toString(totalCount));
        feed.addSimpleExtension(new QName(
                "http://a9.com/-/spec/opensearch/1.1/", "startIndex",
                "opensearch"), Integer.toString(currentPage * itemsPerPage + 1));
        feed.addSimpleExtension(new QName(
                "http://a9.com/-/spec/opensearch/1.1/", "itemsPerPage",
                "opensearch"), Integer.toString(itemsPerPage));

    }

    public Map<String, String> getAlternateAccepts(RequestContext request) {
        if (accepts == null) {
            accepts = new HashMap<String, String>();
            accepts.put("video/*", null); /* doesn't accept multipart related */
            accepts.put("image/png", Constants.LN_ALTERNATE_MULTIPART_RELATED);
            accepts.put("image/jpeg", Constants.LN_ALTERNATE_MULTIPART_RELATED);
            accepts.put("image/gif", Constants.LN_ALTERNATE_MULTIPART_RELATED);
            accepts.put("image/svg+xml",
                    Constants.LN_ALTERNATE_MULTIPART_RELATED);
        }
        return accepts;
    }

    /**
     * Get a media resource
     */
    @Override
    public ResponseContext getMedia(RequestContext request) {
        String feedId = request.getTarget().getParameter("collection");
        String entryId = request.getTarget().getParameter("entry");
        String resourceId = request.getTarget().getParameter("resource");
        InputStream input;
        try {
            // FIXME: this requires a double-fetch of content;
            // storage should return a struct with mimetype and content length
            // and data
            String mimetype = persistence.readFeedEntryResourceType(feedId,
                    Common.toEntryId(entryId), resourceId);
            input = persistence.readFeedEntryResource(feedId,
                    Common.toEntryId(entryId), resourceId);
            MediaResponseContext response = new MediaResponseContext(input,
                    new EntityTag(resourceId), 200);
            response.setContentType(mimetype);
            return response;
        } catch (FileNotFoundException e) {
            return ProviderHelper.notfound(request);
        } catch (IOException e) {
            return ProviderHelper.badrequest(request,
                    "Could not parse resource request");
        }
    }

    /**
     * Get metdata for a media resource
     */
    @Override
    public ResponseContext headMedia(RequestContext request) {
        // TODO: implement HEAD support
        return getMedia(request);
    }

    /**
     * Checks to see if this request needs to be forwarded, and spawns tasks to
     * do so if needed.
     * 
     * @param context
     * @param hostUrl
     */
    protected void pushRawPostIfNeeded(Feed feed, RequestContext request,
            byte[] requestData) {
        IRI ourUri = request.getBaseUri();
        IRI theirUri = feed.getBaseUri();
        if (theirUri != null) {
            String url = theirUri.toString();
            if (!url.startsWith(ourUri.toString())) {
                // TODO: we want to eventually post to naked service url
                String feedId = Common.toFeedIdString(feed.getId());
                int index = url.indexOf(feedId);
                if (index != -1) {
                    url = url.substring(0, index - 1); // trailing slash
                }
                syncToService(feedId, persistence, url);
                pushRawPost(feed, request, requestData, url);
            }
        }
    }

    /**
     * Copies the current request and sends it to the specified host. Called
     * when someone posts to us an entry whose home is on another server: we
     * still ingest a copy but we make sure it gets where it needs to go.
     * 
     * @param context
     * @param hostUrl
     */
    protected void pushRawPost(Feed feed, RequestContext request,
            byte[] requestData, String hostUrl) {
        try {
            // FIXME: eventually want to move off feed ids in POST
            hostUrl = hostUrl + "/" + Common.toFeedIdString(feed.getId());
            new URL(hostUrl); // validates url
            AbderaClient client = new AbderaClient(Abdera.getInstance(),
                    Common.getBuildString());
            ClientResponse response = client.post(hostUrl,
                    new ByteArrayInputStream(requestData), new RequestOptions()
                            .setContentType(request.getContentType()));
            log.debug("Response: " + response.getStatus() + " : "
                    + response.getStatusText());

            log.debug("Forwarded to: " + hostUrl);
        } catch (IOException ioe) {
            log.warn("Connection error while connecting to: " + hostUrl, ioe);
        } catch (Throwable t) {
            log.error("Unexpected error while forwarding to: " + hostUrl, t);
        }
    }

    protected boolean syncToService(String id, Storage storage,
            String serviceUrl) {
        Feed localFeed = fetchFeedFromStorage(id, storage);
        Feed remoteFeed = pullFromService(serviceUrl, id, "count=1");
        if (localFeed != null && remoteFeed != null) {
            // find which is most recent
            long[] entryIds = storage.getEntryIdsForFeedId(id, 0, 1, null,
                    null, null, null, null, null);
            List<Entry> remoteEntries = remoteFeed.getEntries();
            if (entryIds.length == 0) {
                // no local entries: treat as no feed and drop below
                localFeed = null;
            }
            if (remoteEntries.size() == 0) {
                // no remote entries: treat as no feed and drop below
                remoteFeed = null;
            }
            if (localFeed != null && remoteFeed != null) {
                // compare timestamps
                Date localDate = new Date(entryIds[0]);
                Date remoteDate = remoteEntries.get(0).getUpdated();
                if (localDate.before(remoteDate)) {
                    // remote has latest info: pull difference
                    try {
                        remoteFeed = pullFromService(
                                serviceUrl,
                                id,
                                "count=99&after="
                                        + Long.toHexString(localDate.getTime()));
                        ingestFeed(storage, remoteFeed);
                        return true;
                    } catch (IllegalArgumentException e) {
                        log.warn("syncToService: ingest latest remote: invalid feed: "
                                + id
                                + " : "
                                + serviceUrl
                                + " : "
                                + Long.toHexString(localDate.getTime()));
                    } catch (XMLSignatureException e) {
                        log.warn("syncToService: ingest latest remote: invalid signature: "
                                + id
                                + " : "
                                + serviceUrl
                                + " : "
                                + Long.toHexString(localDate.getTime()));
                    } catch (Exception e) {
                        log.error("syncToService: ingest latest remote: unexpected error: "
                                + id
                                + " : "
                                + serviceUrl
                                + " : "
                                + Long.toHexString(localDate.getTime()));
                    }
                } else if (remoteDate.before(localDate)) {
                    // local has latest info: push difference
                    entryIds = storage.getEntryIdsForFeedId(id, 0, 99,
                            remoteDate, null, null, null, null, null);
                    for (long entryId : entryIds) {
                        localFeed.addEntry(getEntry(storage, id, entryId)
                                .getRoot());
                    }
                    return pushToService(localFeed, serviceUrl);
                }
                // otherwise: feeds are in sync
                return true;
            }
        }

        if (localFeed == null && remoteFeed != null) {
            // local is missing: ingest remote
            try {
                ingestFeed(storage, remoteFeed);
                return true;
            } catch (IllegalArgumentException e) {
                log.warn("syncToService: ingest remote: invalid feed: " + id
                        + " : " + serviceUrl);
            } catch (XMLSignatureException e) {
                log.warn("syncToService: ingest remote: invalid signature: "
                        + id + " : " + serviceUrl);
            } catch (Exception e) {
                log.error("syncToService: ingest remote: unexpected error: "
                        + id + " : " + serviceUrl);
            }
        } else if (localFeed != null && remoteFeed == null) {
            // remote is missing: push local with (all?) entries
            long[] entryIds = storage.getEntryIdsForFeedId(id, 0, 99, null,
                    null, null, null, null, null);
            for (long entryId : entryIds) {
                localFeed.addEntry(getEntry(storage, id, entryId).getRoot());
            }
            return pushToService(localFeed, serviceUrl);
        }
        return false;
    }

    protected boolean pushToService(Feed feed, String serviceUrl) {
        try {
            URL url = new URL(serviceUrl + "/"
                    + Common.toFeedIdString(feed.getId()));

            AbderaClient client = new AbderaClient(Abdera.getInstance(),
                    Common.getBuildString());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            feed.writeTo(output);
            ClientResponse response = client
                    .post(url.toString(),
                            new ByteArrayInputStream(output.toByteArray()),
                            new RequestOptions()
                                    .setContentType("application/atom+xml; type=feed; charset=utf-8"));
            log.debug("Response: " + response.getStatus() + " : "
                    + response.getStatusText());
            log.debug("Pushed: " + feed.getId() + " : " + serviceUrl);
            return true;
        } catch (MalformedURLException e) {
            log.error("pushToService: bad url: " + serviceUrl + "/"
                    + Common.toFeedIdString(feed.getId()));
        } catch (IOException e) {
            log.warn("pushToService: could not connect: " + serviceUrl + "/"
                    + Common.toFeedIdString(feed.getId()));
        }
        return false;
    }

    protected Feed pullFromService(String serviceUrl, String entityId,
            String queryString) {
        Feed result = null;
        if (!entityId.startsWith("/")) {
            entityId = "/" + entityId;
        }
        if (queryString != null) {
            queryString = "?" + queryString;
        } else {
            queryString = "";
        }
        String combined = serviceUrl + entityId + queryString;
        log.info("pullFromService: " + combined);
        try {
            AbderaClient client = new AbderaClient(Abdera.getInstance(),
                    Common.getBuildString());
            ClientResponse response = client.get(combined);
            if (response.getType() == ResponseType.SUCCESS) {
                Document<Feed> document = response.getDocument();
                if (document != null) {
                    return document.getRoot();
                } else {
                    log.warn("pull: no document for: " + combined);
                }
            } else {
                log.debug("pull: no document found for: " + combined + " : "
                        + response.getType());
            }
        } catch (ClassCastException cce) {
            log.error("Not a valid feed: " + combined, cce);
        } catch (Exception e) {
            log.error("Could not process feed from relay: " + combined, e);
        }
        return result;
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory
            .getLogger(TrsstAdapter.class);

}