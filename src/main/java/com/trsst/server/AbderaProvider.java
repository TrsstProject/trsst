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
import java.io.StringReader;
import java.net.InetAddress;
import java.util.Collection;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.security.auth.Subject;

import org.apache.abdera.Abdera;
import org.apache.abdera.model.Feed;
import org.apache.abdera.model.Workspace;
import org.apache.abdera.parser.ParseException;
import org.apache.abdera.parser.Parser;
import org.apache.abdera.protocol.Request;
import org.apache.abdera.protocol.server.CollectionAdapter;
import org.apache.abdera.protocol.server.CollectionInfo;
import org.apache.abdera.protocol.server.Filter;
import org.apache.abdera.protocol.server.FilterChain;
import org.apache.abdera.protocol.server.ProviderHelper;
import org.apache.abdera.protocol.server.RequestContext;
import org.apache.abdera.protocol.server.RequestProcessor;
import org.apache.abdera.protocol.server.ResponseContext;
import org.apache.abdera.protocol.server.Target;
import org.apache.abdera.protocol.server.TargetType;
import org.apache.abdera.protocol.server.Transactional;
import org.apache.abdera.protocol.server.WorkspaceInfo;
import org.apache.abdera.protocol.server.WorkspaceManager;
import org.apache.abdera.protocol.server.context.RequestContextWrapper;
import org.apache.abdera.protocol.server.context.ResponseContextException;
import org.apache.abdera.protocol.server.filters.OpenSearchFilter;
import org.apache.abdera.protocol.server.impl.AbstractWorkspaceProvider;
import org.apache.abdera.protocol.server.impl.RegexTargetResolver;
import org.apache.abdera.protocol.server.impl.SimpleCollectionInfo;
import org.apache.abdera.protocol.server.impl.TemplateTargetBuilder;

import com.trsst.Common;

/**
 * Abdera-specific configuration of mapping targets and servlet filters.
 * 
 * @author mpowers
 */
public class AbderaProvider extends AbstractWorkspaceProvider implements
        WorkspaceInfo {

    Hashtable<String, TrsstAdapter> idsToAdapters = new Hashtable<String, TrsstAdapter>();
    String hostname;

    public AbderaProvider() {
        try {
            hostname = InetAddress.getLocalHost().getHostName();
            getStorage(); // force storage to load if necessary
        } catch (Throwable t) {
            log.info("Could not obtain hostname: defaulting to 'localhost'");
            hostname = "localhost";
        }
    }

    @Override
    public void init(Abdera abdera, Map<String, String> properties) {
        // can receive servlet init params here
        super.init(abdera, properties);

        // map paths to handlers
        RegexTargetResolver resolver = new OrderedRegexTargetResolver();
        resolver.setPattern("/service", TargetType.TYPE_SERVICE)
                .setPattern("/(http[^#?]*)/([0-9a-fA-F]{11})", TargetType.TYPE_ENTRY,
                        "collection", "entry") // external entry
                .setPattern("/(http[^#?]*)", TargetType.TYPE_COLLECTION,
                        "collection") // external feed
                .setPattern("/([^/#?]+)/([^/#?]+)/([^/#?]+)(\\?[^#]*)?",
                        TargetType.TYPE_MEDIA, "collection", "entry",
                        "resource") 
                .setPattern("/([^/#?]+)/([^/#?]+)(\\?[^#]*)?",
                        TargetType.TYPE_ENTRY, "collection", "entry")
                .setPattern("/([^/#?]+);categories", 
                        TargetType.TYPE_CATEGORIES, "collection")
                .setPattern("/([^/#?;]+)(\\?[^#]*)?",
                        TargetType.TYPE_COLLECTION, "collection");

        super.setTargetResolver(resolver);

        // url construction templates
        setTargetBuilder(new TemplateTargetBuilder()
                .setTemplate(TargetType.TYPE_SERVICE, "{target_base}")
                .setTemplate(TargetType.TYPE_COLLECTION,
                        "{target_base}/{collection}{-opt|?|q,c,s,p,l,i,o}{-join|&|q,c,s,p,l,i,o}")
                .setTemplate(TargetType.TYPE_CATEGORIES,
                        "{target_base}/{collection};categories")
                .setTemplate(TargetType.TYPE_ENTRY,
                        "{target_base}/{collection}/{entry}"));

        addWorkspace(this);
        addFilter(new PaginationFilter());
        addFilter(new OpenSearchFilter()
                .setShortName("Trsst Search")
                .setDescription("Search on entry metadata.")
                .setTags("test", "example", "opensearch")
                .setContact("admin@trsst.com")
                .setTemplate(
                        "{target_base}/?q={searchTerms}&count={count?}&page={startPage?}&offset={startIndex?}&before={beforeDate?}&after={afterDate?}")
                // .setTemplate(
                // "{target_base}/?q={searchTerms}&c={count?}&s={startIndex?}&p={startPage?}&l={language?}&i={indexEncoding?}&o={outputEncoding?}")
                .mapTargetParameter("q", "searchTerms")
                .mapTargetParameter("count", "count")
                .mapTargetParameter("before", "beforeDate")
                .mapTargetParameter("after", "afterDates")
                .mapTargetParameter("page", "startPage")
                .mapTargetParameter("offset", "startIndex"));
        // .mapTargetParameter("l", "language")
        // .mapTargetParameter("i", "inputEncoding")
        // .mapTargetParameter("o", "outputEncoding"));

    }

    public ResponseContext process(RequestContext request) {
        Target target = request.getTarget();
        if (target == null || target.getType() == TargetType.TYPE_NOT_FOUND) {
            return ProviderHelper.notfound(request);
        }

        TargetType type = target.getType();
        RequestProcessor processor = this.requestProcessors.get(type);
        if (processor == null) {
            return ProviderHelper.notfound(request);
        }

        WorkspaceManager wm = getWorkspaceManager(request);
        CollectionAdapter adapter = wm.getCollectionAdapter(request);
        Transactional transaction = adapter instanceof Transactional ? (Transactional) adapter
                : null;
        ResponseContext response = null;
        try {
            transactionStart(transaction, request);
            response = processor.process(request, wm, adapter);
            response = response != null ? response : processExtensionRequest(
                    request, adapter);
        } catch (Throwable e) {
            if (e instanceof ResponseContextException) {
                ResponseContextException rce = (ResponseContextException) e;
                if (rce.getStatusCode() >= 400 && rce.getStatusCode() < 500) {
                    // don't report routine 4xx HTTP errors
                    log.info("info: ", e);
                } else {
                    log.error("inner error: ", e);
                }
            } else {
                log.error("outer error: ", e);
            }
            transactionCompensate(transaction, request, e);
            response = createErrorResponse(request, e);
            return response;
        } finally {
            transactionEnd(transaction, request, response);
        }
        return response != null ? response : ProviderHelper.badrequest(request);
    }

    // @Override
    // public ResponseContext process(RequestContext request) {
    // try {
    // log.info(request.getMethod().toString() + " "
    // + request.getUri().toString());
    // return super.process(request);
    // } catch (Throwable t) {
    // log.info(request.getMethod().toString() + " "
    // + request.getUri().toString());
    // log.error("Unexpected error: " + t);
    // }
    // return null;
    // }
    //
    /**
     * Returns null to function in containers with constrained permissions;
     * Trsst servers generally don't need http security contexts.
     */
    @Override
    public Subject resolveSubject(RequestContext request) {
        // return null to work in containers with constrained permissions
        return null;
    }

    /**
     * Override to return a custom storage instance. This implementation
     * defaults to a single shared LuceneStorage instance.
     * 
     * @param feedId
     *            a hint for implementors
     * @return a Storage for the specified feed id
     */
    protected Storage getStorage() {
        if (sharedStorage == null) {
            try {
                Storage clientStorage = new FileStorage(Common.getClientRoot());
                Storage cacheStorage = new FileStorage(Common.getServerRoot());
                sharedStorage = new LuceneStorage(cacheStorage, clientStorage);
            } catch (IOException e) {
                log.error("Could not initialize storage", e);
            }
        }
        return sharedStorage;
    }

    /**
     * Override to return a custom adapter instance. This implementation
     * defaults to TrsstAdapter configured to use the result of
     * getStorageFromFeedId.
     * 
     * @param feedId
     *            a hint for implementors
     * @return a TrsstAdapter for the specified feed id
     */
    protected TrsstAdapter getAdapterForFeedId(RequestContext request)
            throws IOException {
        return new TrsstAdapter(request, getStorage());
    }

    private static Storage sharedStorage;

    public CollectionAdapter getCollectionAdapter(RequestContext request) {
        String feedId = request.getTarget().getParameter("collection");
        if (feedId != null) {
            try {
                TrsstAdapter result = idsToAdapters.get(feedId);
                if (result == null) {
                    result = getAdapterForFeedId(request);
                    idsToAdapters.put(feedId, result);
                }
                return result;
            } catch (Throwable t) {
                log.error("Not found: id: " + feedId, t);
                return null;
            }
        } else {
            log.error("No id found: " + request.getTargetPath());
            return null;
        }
    }

    public class PaginationFilter implements Filter {
        public ResponseContext filter(RequestContext request, FilterChain chain) {
            RequestContextWrapper rcw = new RequestContextWrapper(request);
            rcw.setAttribute("offset", 10);
            rcw.setAttribute("count", 10);
            return chain.next(rcw);
        }
    }

    private final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(this
            .getClass());

    public String getTitle(RequestContext requsest) {
        // workspace info title
        return hostname;
    }

    /**
     * Returns some or all of the most active feeds hosted on this server. This
     * implementation calls Storage.getFeedIds(0,100). Override to think
     * different.
     */
    protected String[] getFeedIds(RequestContext request) {
        // arbitrary cap: get up to most active hundred.
        return getStorage().getFeedIds(0, 100);
    }

    public Collection<CollectionInfo> getCollections(RequestContext request) {
        LinkedList<CollectionInfo> result = new LinkedList<CollectionInfo>();
        Feed feed;
        Parser parser = Abdera.getInstance().getParser();
        CollectionInfo info;
        Storage storage = getStorage();
        for (String id : getFeedIds(request)) {
            try {
                feed = (Feed) parser.parse(
                        new StringReader(storage.readFeed(id))).getRoot();
                String title = feed.getTitle();
                // default title to id if null
                if (title == null) {
                    title = id;
                }
                info = new SimpleCollectionInfo(title, id, "text/plain",
                        "text/html", "text/xml", "image/png", "image/jpeg",
                        "image/gif", "image/svg+xml", "video/mp4");
                result.add(info);
            } catch (ParseException e) {
                log.warn("Could not parse collection info for feed: " + id
                        + " : " + e.toString());
            } catch (IOException e) {
                log.warn("Could not read collection info for feed: " + id
                        + " : " + e.toString());
            }
        }
        return result;
    }

    public Workspace asWorkspaceElement(RequestContext request) {
        Workspace workspace = request.getAbdera().getFactory().newWorkspace();
        workspace.setTitle(getTitle(request));
        for (CollectionInfo collection : getCollections(request))
            workspace.addCollection(collection.asCollectionElement(request));
        return workspace;
    }

    private static final class OrderedRegexTargetResolver extends
            RegexTargetResolver {

        // patterns is final in super
        Map<Pattern, TargetType> orderedPatterns = new LinkedHashMap<Pattern, TargetType>();

        // override to intercept pattern order
        public RegexTargetResolver setPattern(String pattern, TargetType type,
                String... fields) {
            // ugh: don't call super so we don't compile twice
            Pattern p = Pattern.compile(pattern);
            orderedPatterns.put(p, type);
            this.fields.put(p, fields);
            return this;
        }

        // override to exclude BaseTargetPath (and now to use ordered patterns)
        public Target resolve(Request request) {
            RequestContext context = (RequestContext) request;
            String uri = context.getTargetPath();
            if (uri.startsWith(context.getTargetBasePath())) {
                uri = uri.substring(context.getTargetBasePath().length());
            }
            // note: now first matching pattern wins
            for (Pattern pattern : orderedPatterns.keySet()) {
                Matcher matcher = pattern.matcher(uri);
                if (matcher.lookingAt()) {
                    TargetType type = this.orderedPatterns.get(pattern);
                    String[] fields = this.fields.get(pattern);
                    return getTarget(type, context, matcher, fields);
                }
            }
            return null;
        }
    };
}
