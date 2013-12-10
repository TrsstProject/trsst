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
import java.io.StringReader;
import java.security.PublicKey;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.namespace.QName;

import org.apache.abdera.Abdera;
import org.apache.abdera.i18n.templates.Template;
import org.apache.abdera.model.Document;
import org.apache.abdera.model.Element;
import org.apache.abdera.model.Entry;
import org.apache.abdera.model.Feed;
import org.apache.abdera.model.Link;
import org.apache.abdera.model.Person;
import org.apache.abdera.protocol.server.ProviderHelper;
import org.apache.abdera.protocol.server.RequestContext;
import org.apache.abdera.protocol.server.ResponseContext;
import org.apache.abdera.protocol.server.Target;
import org.apache.abdera.protocol.server.TargetType;
import org.apache.abdera.protocol.server.context.ResponseContextException;
import org.apache.abdera.protocol.server.context.StreamWriterResponseContext;
import org.apache.abdera.protocol.server.impl.AbstractCollectionAdapter;
import org.apache.abdera.security.AbderaSecurity;
import org.apache.abdera.security.Signature;
import org.apache.abdera.security.SignatureOptions;
import org.apache.abdera.util.Constants;
import org.apache.abdera.writer.StreamWriter;

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

public class TrsstAdapter extends AbstractCollectionAdapter {

    private final static Template paging_template = new Template(
            "{collection}?{-join|&|count,page}");

    String accountId;
    Feed feed;
    Storage persistence;

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
    public TrsstAdapter(String id, Storage storage)
            throws FileNotFoundException, IOException {
        this.accountId = id;
        persistence = storage;
        try {
            feed = (Feed) Abdera.getInstance().getParser()
                    .parse(new StringReader(persistence.readFeed(accountId)))
                    .getRoot().clone();
        } catch (FileNotFoundException e) {
            feed = null;
        }
    }

    @Override
    public String getId(RequestContext request) {
        return accountId;
    }

    @Override
    public String getAuthor(RequestContext request)
            throws ResponseContextException {
        Person author = feed.getAuthor();
        if (author != null) {
            return author.getName();
        }
        return null;
    }

    @Override
    protected Feed createFeedBase(RequestContext request)
            throws ResponseContextException {
        return (Feed) feed.clone();
    }

    public String getTitle(RequestContext request) {
        return feed.getTitle();
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
        if (feed != null) {
            // make a copy of the current template
            Feed result = (Feed) feed.clone();
            // add requested entries
            getEntries(request, result);
            return ProviderHelper.returnBase(result, 200, result.getUpdated())
                    .setEntityTag(ProviderHelper.calculateEntityTag(result));
        } else {
            return ProviderHelper.notfound(request);
        }
    }

    /**
     * Returns a feed document containing the single requested entry. NOTE: this
     * is a deviation from atompub.
     */
    public ResponseContext getEntry(RequestContext context) {
        if (feed != null) {
            // make a copy of the current template
            Feed result = (Feed) feed.clone();
            // add requested entries
            String entryId = context.getTarget().getParameter("entry");
            Document<Entry> entry = getEntry(context, entryId);
            if (entry != null) {
                result.addEntry(entry.getRoot());
            }
            return ProviderHelper.returnBase(result, 200, result.getUpdated())
                    .setEntityTag(ProviderHelper.calculateEntityTag(result));
        }
        return ProviderHelper.notfound(context);
    }

    private Document<Entry> getEntry(RequestContext context, String entryId) {
        try {
            return context
                    .getAbdera()
                    .getParser()
                    .parse(new StringReader(persistence.readEntry(accountId,
                            entryId)));
        } catch (FileNotFoundException fnfe) {
            // fall through
        } catch (Exception e) {
            log.error("Unexpected error: " + accountId + " : " + entryId, e);
        }
        return null;
    }

    /**
     * Accepts a signed feed document containing one or more signed entries. All
     * signatures must be valid or the entire transaction will be rejected.
     * NOTE: this is a deviation from atompub.
     */
    public ResponseContext postEntry(RequestContext request) {
        if (request.isAtom()) {
            try {
                // we require a feed entity (not solo entries like atompub)
                Feed incomingFeed = (Feed) request.getDocument().getRoot()
                        .clone();

                // grab the signing key
                Element signingElement = incomingFeed.getFirstChild(new QName(
                        Common.NS_URI, Common.SIGN));
                if (signingElement == null) {
                    return ProviderHelper
                            .badrequest(request,
                                    "Could not find signing key for feed: "
                                            + accountId);
                }

                // verify that the key matches the id
                PublicKey publicKey = Common.toPublicKey(signingElement
                        .getText());
                if (incomingFeed.getId() == null
                        || !incomingFeed.getId().toString()
                                .equals(Common.toFeedId(publicKey))) {
                    return ProviderHelper.forbidden(request,
                            "Signing key does not match feed id: "
                                    + incomingFeed.getId());
                }

                // prep the verifier
                AbderaSecurity security = new AbderaSecurity(
                        Abdera.getInstance());
                Signature signature = security.getSignature();
                SignatureOptions options = signature
                        .getDefaultSignatureOptions();
                options.setSigningAlgorithm("http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha1");
                options.setSignLinks(false);
                options.setPublicKey(publicKey);

                // validate, persist, and remove each entry
                List<Entry> entries = incomingFeed.getEntries();
                for (Entry entry : entries) {
                    if (signature.verify(entry, options)) {
                        // entry verified: now persist
                        Date date = entry.getPublished();
                        if (date == null) {
                            // fall back to updated if publish not set
                            date = entry.getUpdated();
                        }
                        String key = entry.getId().toString(); // createKey(request);
                        persistence.updateEntry(accountId, key, date,
                                entry.toString());
                    } else {
                        log.warn("Could not verify signature for entry with id: "
                                + accountId);
                        return ProviderHelper.badrequest(request,
                                "Could not verify signature for entry with id: "
                                        + entry.getId() + " : " + accountId);
                    }
                    // remove from feed parent
                    entry.discard();
                }
                // setEditDetail(request, entry, key);
                // String edit = entry.getEditLinkResolvedHref().toString();

                // remove all links before signing
                for (Link link : incomingFeed.getLinks()) {
                    link.discard();
                }

                // now validate feed signature sans entries
                if (!signature.verify(incomingFeed, options)) {
                    log.warn("Could not verify signature for feed with id: "
                            + accountId);
                    return ProviderHelper.badrequest(request,
                            "Could not verify signature for feed with id: "
                                    + accountId);
                }

                // persist feed
                if (feed == null || feed.getUpdated() == null
                        || feed.getUpdated().before(incomingFeed.getUpdated())) {
                    persistence.updateFeed(accountId,
                            incomingFeed.getUpdated(), incomingFeed.toString());
                    // retain a fresh copy as new official template feed
                    feed = (Feed) incomingFeed.clone();
                }

                return ProviderHelper.returnBase((Feed) request.getDocument()
                        .getRoot().clone(), 201, null); // FIXME:.setLocation(edit);
            } catch (FileNotFoundException fnfe) {
                return ProviderHelper.notfound(request, "Not found: "
                        + accountId);
            } catch (Exception e) {
                log.warn("Bad request: " + feed, e);
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
        // TODO: post delete notification entry referencing the specified entry
        Target target = request.getTarget();
        String entryId = target.getParameter("entry");
        try {
            persistence.deleteEntry(accountId, entryId);
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

    private void getEntries(RequestContext context, Feed feed) {
        int length = ProviderHelper.getPageSize(context, "count", 25);
        int offset = ProviderHelper.getOffset(context, "page", length);
        String _page = context.getParameter("page");
        int page = (_page != null) ? Integer.parseInt(_page) : 0;
        addPagingLinks(context, feed, page, length);
        String[] entryIds = persistence.getEntryIdsForFeedId(accountId, offset
                * length, length, null, null, null);
        for (String id : entryIds) {
            Entry entry = getEntry(context, id).getRoot();
            feed.addEntry((Entry) entry.clone());
        }
    }

    private void addPagingLinks(RequestContext request, Feed feed,
            int currentpage, int count) {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("collection", request.getTarget().getParameter("collection"));
        params.put("count", count);
        params.put("page", currentpage + 1);
        String next = paging_template.expand(params);
        next = request.getResolvedUri().resolve(next).toString();
        feed.addLink(next, "next");
        if (currentpage > 0) {
            params.put("page", currentpage - 1);
            String prev = paging_template.expand(params);
            prev = request.getResolvedUri().resolve(prev).toString();
            feed.addLink(prev, "previous");
        }
        params.put("page", 0);
        String current = paging_template.expand(params);
        current = request.getResolvedUri().resolve(current).toString();
        feed.addLink(current, "current");
    }

    private final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(this
            .getClass());
}
