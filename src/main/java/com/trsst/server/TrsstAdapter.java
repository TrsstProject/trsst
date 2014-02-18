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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.PublicKey;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.xml.crypto.dsig.XMLSignatureException;
import javax.xml.namespace.QName;

import org.apache.abdera.Abdera;
import org.apache.abdera.ext.rss.RssConstants;
import org.apache.abdera.i18n.iri.IRI;
import org.apache.abdera.i18n.iri.IRISyntaxException;
import org.apache.abdera.i18n.templates.Template;
import org.apache.abdera.model.AtomDate;
import org.apache.abdera.model.Content;
import org.apache.abdera.model.Document;
import org.apache.abdera.model.Element;
import org.apache.abdera.model.Entry;
import org.apache.abdera.model.Feed;
import org.apache.abdera.model.Link;
import org.apache.abdera.model.Person;
import org.apache.abdera.model.Text;
import org.apache.abdera.parser.ParseException;
import org.apache.abdera.protocol.server.ProviderHelper;
import org.apache.abdera.protocol.server.RequestContext;
import org.apache.abdera.protocol.server.RequestContext.Scope;
import org.apache.abdera.protocol.server.ResponseContext;
import org.apache.abdera.protocol.server.Target;
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

public class TrsstAdapter extends AbstractMultipartAdapter {

    private final static Template paging_template = new Template(
            "{collection}?{-join|&|q,verb,mention,tag,before,after,count,page}");

    String feedId;
    Storage persistence;
    Map<String, String> accepts;

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
    public TrsstAdapter(RequestContext request, Storage storage)
            throws FileNotFoundException, IOException {
        persistence = storage;
        feedId = Common.decodeURL(request.getTarget()
                .getParameter("collection"));
    }

    /**
     * Returns the current feed to service this request, fetching from the
     * current request, from local storage, or from remote peers as needed.
     */
    private Feed currentFeed(RequestContext request) throws ParseException,
            FileNotFoundException, IOException {
        Feed feed = null;
        RequestContextWrapper wrapper = new RequestContextWrapper(request);
        System.err.println(new Date().toString() + " "
                + wrapper.getTargetPath());

        // fetch from request context
        feed = (Feed) wrapper.getAttribute(Scope.REQUEST, "com.trsst.Feed");

        if (feed == null) {
            // fetch from storage
            feed = fetchFeedFromStorage(feedId);
            if (feed != null) {
                // trigger async fetch in case we're stale
                fetchLaterFromRelay(feedId, request);
            }
        }

        if (feed == null) {
            // fetch from network
            if (!Common.isAccountId(feedId)) {
                // external feeds don't relay:
                // because they're unsigned,
                // we fetch directly from source
                feed = fetchFromExternalSource(feedId);
            } else {
                // attempt to fetch from relay peer
                feed = fetchFromRelay(request);
            }
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

    private Feed fetchFeedFromStorage(String feedId) {
        Feed feed = null;
        try {
            log.debug("fetchFeedFromStorage: " + feedId);
            feed = (Feed) Abdera.getInstance().getParser()
                    .parse(new StringReader(persistence.readFeed(feedId)))
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
     * pull. This implementation spawns a new thread, but others should
     * implement a heuristic to queue this task for later based on the
     * likelihood that a refetch is needed, e.g. factoring in time since last
     * update and frequency of updates, etc.
     */
    protected void fetchLaterFromRelay(final String feedId,
            final RequestContext request) {
        doLater(new Runnable() {
            public void run() {
                log.debug("fetchLaterFromRelay: starting: "
                        + request.getResolvedUri());
                if (!Common.isAccountId(feedId)) {
                    // external feeds don't relay:
                    // because they're unsigned,
                    // we fetch directly from source
                    fetchFromExternalSource(feedId);
                } else {
                    // attempt to fetch from relay peer
                    fetchFromRelay(request);
                }
            }
        });
    }

    /**
     * Hook for subclasses to control new process creation.
     * 
     * @param runnable
     */
    protected void doLater(Runnable runnable) {
        new Thread(runnable).start();
    }

    /**
     * 
     * @param request
     * @return
     */
    private Feed fetchFromRelay(RequestContext request) {
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
        if (relays == null || relays.size() <= limit) {
            URL relayPeer = getRelayPeer();
            if (relayPeer != null) {
                fetchFromServiceUrl(request, getRelayPeer());
            } else {
                log.debug("No relay peer available for request: "
                        + request.getResolvedUri());
            }
        }
        return result;
    }

    /**
     * Returns a relay peer to use to fetch contents. Implementors should return
     * a url chosen from an evenly or randomly distributed mix of known trsst
     * servers based on the home urls of this servers hosted content. This
     * implementation returns null.
     */
    protected URL getRelayPeer() {
        return null;
    }

    /**
     * Fetch from the specified trsst service url, validate it, ingest it, and
     * return the returned feed.
     */
    private Feed fetchFromServiceUrl(RequestContext request, URL serviceUrl) {
        Feed result = null;
        log.info("fetchFromServiceUrl: uri: " + request.getResolvedUri());
        IRI uri = request.getResolvedUri();
        String hostName = uri.getHost();
        String queryString = uri.getQuery();
        if (queryString == null) {
            queryString = "";
        }
        if (queryString.indexOf("relay=" + hostName) != -1) {
            // if we're alerady in the list of relay peers
            log.error("Unexpected relay loopback: ignoring request");
            return result;
        }
        if (queryString.length() > 0) {
            queryString = queryString + '&';
        }
        // add self as relay
        queryString = queryString + "relay=" + hostName;

        try {
            URL url = new URL(serviceUrl.toString() + '?' + queryString);
            log.info("fetchFromServiceUrl: " + url);
            InputStream input = url.openStream();
            result = (Feed) Abdera.getInstance().getParser().parse(input)
                    .getRoot();
            ingestFeed(result);
        } catch (FileNotFoundException fnfe) {
            log.warn("Could not fetch from relay: " + feedId);
        } catch (MalformedURLException urle) {
            log.error("Could not construct relay fetch url: "
                    + serviceUrl.toString() + '?' + queryString);
        } catch (IOException ioe) {
            log.error("Could not connect: " + feedId, ioe);
        } catch (ClassCastException cce) {
            log.error("Not a valid feed: " + feedId, cce);
        } catch (Exception e) {
            log.error("Could not process feed from relay: " + feedId, e);
        }
        return result;
    }

    /**
     * For external feed ids: fetch directly from external source, convert to a
     * trsst feed, (optionally validate it), (optionally persist it), and return
     * the feed.
     */
    private Feed fetchFromExternalSource(String feedId) {
        Feed result = null;
        try {
            feedId = Common.decodeURL(feedId);
            URL url = new URL(feedId);
            InputStream input = url.openStream();
            result = (Feed) Abdera.getInstance().getParser().parse(input)
                    .getRoot();

            // convert from rss if needed
            if (result.getClass().getName().indexOf("RssFeed") != -1) {
                result = convertFromRSS(feedId, result);
            }
            if (result != null) {
                // process and persist external feed
                final String id = feedId;
                final Feed copy = (Feed) result.clone();
                // process one immediately
                ingestExternalFeed(id, copy, 1);
                // process remained concurrently so we can return to user
                doLater(new Runnable() {
                    public void run() {
                        try {
                            ingestExternalFeed(id, copy, 100);
                        } catch (Exception e) {
                            log.error("Could not process external feed: " + id,
                                    e);
                        }
                    }

                });
            }

        } catch (FileNotFoundException fnfe) {
            log.warn("Could not fetch from external source: " + feedId);
        } catch (MalformedURLException urle) {
            log.error("Not a valid external feed id: " + feedId);
        } catch (IOException ioe) {
            log.error("Could not connect: " + feedId, ioe);
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
            return ProviderHelper.returnBase(result, 200, result.getUpdated())
                    .setEntityTag(ProviderHelper.calculateEntityTag(result));
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
            Document<Entry> entry = getEntry(request, Common.toEntryId(entryId));
            if (entry != null) {
                result.addEntry(entry.getRoot());
            } else {
                return ProviderHelper.notfound(request);
            }
            return ProviderHelper.returnBase(result, 200, result.getUpdated())
                    .setEntityTag(ProviderHelper.calculateEntityTag(result));
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

    private Document<Entry> getEntry(RequestContext context, long entryId) {
        try {
            // NOTE: by this point currentFeed() will have fetched
            // the requested entry via relay if needed
            // FIXME: this is not currently working; need a test case

            // fetch from local storage
            return context
                    .getAbdera()
                    .getParser()
                    .parse(new StringReader(persistence.readEntry(feedId,
                            entryId)));
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
                List<MultipartRelatedPost> posts = getMultipartRelatedData(request);
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
                        ingestFeed(incomingFeed);
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
    protected void ingestFeed(Feed feed) throws XMLSignatureException,
            IllegalArgumentException, Exception {

        // clone a copy so we can manipulate
        feed = (Feed) feed.clone();

        // validate feed
        Date lastUpdated = feed.getUpdated();
        if (lastUpdated == null) {
            throw new IllegalArgumentException(
                    "Feed update timestamp is required: " + feedId);
        }
        if (lastUpdated.after(new Date(
                System.currentTimeMillis() + 1000 * 60 * 5))) {
            // allows five minutes of variance
            throw new IllegalArgumentException(
                    "Feed update timestamp cannot be in the future: " + feedId);
        }

        // grab the signing key
        Element signingElement = feed.getFirstChild(new QName(Common.NS_URI,
                Common.SIGN));
        if (signingElement == null) {
            throw new XMLSignatureException(
                    "Could not find signing key for feed: " + feedId);
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
                log.warn("Could not verify signature for entry with id: "
                        + feedId);
                throw new XMLSignatureException(
                        "Could not verify signature for entry with id: "
                                + entry.getId() + " : " + feedId);
            }
            // remove from feed parent
            entry.discard();
            try {
                // see if this file already exists
                persistence.readEntry(feedId, Common.toEntryId(entry.getId()));
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
            log.warn("Could not verify signature for feed with id: " + feedId);
            throw new XMLSignatureException(
                    "Could not verify signature for feed with id: " + feedId);
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

            // convert existing entry id to a trsst timestamp-based id
            String existing = entry.getId().toString();
            long timestamp = entry.getUpdated().getTime();

            // RSS feeds don't have millisecond precision
            // so we need to add it to avoid duplicate ids
            if (timestamp % 1000 == 0) {
                timestamp = timestamp + existing.hashCode() % 1000;
            }

            try {
                // see if this file already exists
                persistence.readEntry(feedId, timestamp);
                // this file exists; remove from processing
                entries.remove(entry);
            } catch (FileNotFoundException e) {
                // we don't already have it:
                entry.setId(Common.toEntryUrn(feedId, timestamp));
            }
            // remove from feed parent
            entry.discard();

            if (++count > limit) {
                break;
            }
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
     * Converts from RSS parser's read-only Feed to a mutable Feed.
     */
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
                long timestamp = updated.getTime();
                if (mostRecent == null || mostRecent.before(updated)) {
                    mostRecent = updated;
                }
                Object existing = null;
                try {
                    existing = entry.getId();
                } catch (IRISyntaxException irie) {
                    // EFF's entry id's have spaces
                    // "<guid isPermaLink="false">78822 at https://www.eff.org</guid>"
                }
                if (existing == null) {
                    existing = updated;
                }

                // RSS feeds don't have millisecond precision
                // so we need to add it to avoid duplicate ids
                if (timestamp % 1000 == 0) {
                    timestamp = timestamp + existing.hashCode() % 1000;
                }
                converted.setId(Common.toEntryUrn(feedId, timestamp));
                converted.setUpdated(entry.getUpdated());
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
            Feed existingFeed = fetchFeedFromStorage(feedId);
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

                // WORKAROUND: loading the stream and making our own parser
                // works
                byte[] bytes = Common.readFully(request.getInputStream());
                Feed incomingFeed = (Feed) Abdera.getInstance().getParser()
                        .parse(new ByteArrayInputStream(bytes)).getRoot();

                // we require a feed entity (not solo entries like atompub)
                ingestFeed(incomingFeed);
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
     * DELETE operations should instead replace the entry is a placeholder
     * expiration notice to maintain blogchain integrity. NOTE: this is a
     * deviation from atompub.
     */
    public ResponseContext deleteEntry(RequestContext request) {
        // TODO: post revocation entry referencing the specified entry
        Target target = request.getTarget();
        String entryId = target.getParameter("entry");
        try {
            persistence.deleteEntry(feedId, Common.toEntryId(entryId));
        } catch (IOException ioe) {
            return ProviderHelper.servererror(request, ioe);
        }
        return ProviderHelper.nocontent();
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

    private void fetchEntriesFromStorage(RequestContext context, Feed feed)
            throws FileNotFoundException, IOException {
        String searchTerms = (String) context.getAttribute(Scope.REQUEST,
                "OpenSearch__searchTerms");
        Date beginDate = null;

        String verb = context.getParameter("verb");

        String[] mentions = null;
        List<String> mentionList = context.getParameters("mention");
        if (mentionList != null) {
            mentions = mentionList.toArray(new String[0]);
        }

        String[] tags = null;
        List<String> tagList = context.getParameters("tag");
        if (tagList != null) {
            tags = tagList.toArray(new String[0]);
        }

        String after = context.getParameter("after");
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
        String before = context.getParameter("before");
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

        int length = ProviderHelper.getPageSize(context, "count", 25);
        // int offset = ProviderHelper.getOffset(context, "page", length);
        int maxresults = 999; // arbitrary: clients that need larger should page
                              // themselves by date
        String _page = context.getParameter("page");
        int page = (_page != null) ? Integer.parseInt(_page) : 0;
        long[] entryIds = persistence.getEntryIdsForFeedId(feedId, 0,
                maxresults, beginDate, endDate, searchTerms, mentions, tags,
                verb);
        addPagingLinks(context, feed, page, length, entryIds.length,
                searchTerms, before, after, mentions, tags, verb);
        int start = page * length;
        int end = Math.min(entryIds.length, start + length);
        Document<Entry> document;
        for (int i = start; i < end; i++) {
            document = getEntry(context, entryIds[i]);
            if (document != null) {
                feed.addEntry((Entry) document.getRoot().clone());
            } else {
                log.error("Could not find entry for id: " + feedId + " : "
                        + Long.toHexString(entryIds[i]));
            }
        }
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
        current = request.getResolvedUri().resolve(current).toString();
        feed.addLink(current, "current");
        if (totalCount > (currentPage + 1) * itemsPerPage) {
            params.put("page", currentPage + 1);
            String next = paging_template.expand(params);
            next = request.getResolvedUri().resolve(next).toString();
            feed.addLink(next, "next");
        }
        if (currentPage > 0) {
            params.put("page", currentPage - 1);
            String prev = paging_template.expand(params);
            prev = request.getResolvedUri().resolve(prev).toString();
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

    private final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(this
            .getClass());

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

}