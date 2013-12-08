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

import java.util.Hashtable;

import org.apache.abdera.protocol.server.CollectionAdapter;
import org.apache.abdera.protocol.server.Filter;
import org.apache.abdera.protocol.server.FilterChain;
import org.apache.abdera.protocol.server.RequestContext;
import org.apache.abdera.protocol.server.ResponseContext;
import org.apache.abdera.protocol.server.TargetType;
import org.apache.abdera.protocol.server.context.RequestContextWrapper;
import org.apache.abdera.protocol.server.filters.OpenSearchFilter;
import org.apache.abdera.protocol.server.impl.AbstractWorkspaceProvider;
import org.apache.abdera.protocol.server.impl.RegexTargetResolver;
import org.apache.abdera.protocol.server.impl.SimpleWorkspaceInfo;
import org.apache.abdera.protocol.server.impl.TemplateTargetBuilder;

/**
 * Abdera-specific configuration of mapping targets and servlet filters.
 * 
 * @author mpowers
 */
public class AbderaProvider extends AbstractWorkspaceProvider {

    Hashtable<String, TrsstAdapter> idsToAdapters = new Hashtable<String, TrsstAdapter>();
    SimpleWorkspaceInfo workspace = new SimpleWorkspaceInfo();

    public AbderaProvider() {

        // map paths to handlers
        super.setTargetResolver(new RegexTargetResolver()
                .setPattern("/(\\?[^#]*)?", TargetType.TYPE_SERVICE)
                .setPattern("/([^/#?]+);categories",
                        TargetType.TYPE_CATEGORIES, "collection")
                .setPattern("/([^/#?;]+)(\\?[^#]*)?",
                        TargetType.TYPE_COLLECTION, "collection")
                .setPattern("/([^/#?]+)/([^/#?]+)(\\?[^#]*)?",
                        TargetType.TYPE_ENTRY, "collection", "entry"));

        // url construction templates
        setTargetBuilder(new TemplateTargetBuilder()
                .setTemplate(TargetType.TYPE_SERVICE, "{target_base}")
                .setTemplate(TargetType.TYPE_COLLECTION,
                        "{target_base}/trsst/{collection}{-opt|?|q,c,s,p,l,i,o}{-join|&|q,c,s,p,l,i,o}")
                .setTemplate(TargetType.TYPE_CATEGORIES,
                        "{target_base}/trsst/{collection};categories")
                .setTemplate(TargetType.TYPE_ENTRY,
                        "{target_base}/trsst/{collection}/{entry}"));

        workspace.setTitle("Trsst Feeds");
        addWorkspace(workspace);

        addFilter(new PaginationFilter());
        addFilter(new OpenSearchFilter()
                .setShortName("Trsst Search")
                .setDescription("Search on entry subjects.")
                .setTags("test", "example", "opensearch")
                .setContact("admin@trsst.com")
                .setTemplate(
                        "{target_base}/trsst?q={searchTerms}&c={count?}&s={startIndex?}&p={startPage?}&l={language?}&i={indexEncoding?}&o={outputEncoding?}")
                .mapTargetParameter("q", "searchTerms")
                .mapTargetParameter("c", "count")
                .mapTargetParameter("s", "startIndex")
                .mapTargetParameter("p", "startPage")
                .mapTargetParameter("l", "language")
                .mapTargetParameter("i", "inputEncoding")
                .mapTargetParameter("o", "outputEncoding"));

    }

    protected Storage getStorageForFeedId(String feedId) {
        if (sharedStorage == null) {
            sharedStorage = new FileStorage();
        }
        return sharedStorage;
    }

    private static Storage sharedStorage;

    public CollectionAdapter getCollectionAdapter(RequestContext request) {
        String feedId = request.getTarget().getParameter("collection");
        if (feedId != null) {
            try {
                TrsstAdapter result = idsToAdapters.get(feedId);
                if (result == null) {
                    result = new TrsstAdapter(feedId, getStorageForFeedId(feedId));
                    workspace.addCollection(result);
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
}
