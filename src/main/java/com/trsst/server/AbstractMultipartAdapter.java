/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */
package com.trsst.server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.mail.Header;
import javax.mail.MessagingException;
import javax.mail.internet.InternetHeaders;

import org.apache.abdera.model.Document;
import org.apache.abdera.model.Element;
import org.apache.abdera.model.Source;
import org.apache.abdera.parser.ParseException;
import org.apache.abdera.parser.Parser;
import org.apache.abdera.protocol.server.RequestContext;
import org.apache.abdera.protocol.server.impl.AbstractCollectionAdapter;
import org.apache.abdera.protocol.server.multipart.MultipartInputStream;
import org.apache.abdera.protocol.server.multipart.MultipartRelatedCollectionInfo;
import org.apache.abdera.util.Constants;
import org.apache.abdera.util.MimeTypeHelper;
import org.apache.commons.codec.binary.Base64;

@SuppressWarnings("unchecked")
public abstract class AbstractMultipartAdapter extends
        AbstractCollectionAdapter implements MultipartRelatedCollectionInfo {

    private static final String CONTENT_TYPE_HEADER = "content-type";
    private static final String CONTENT_ID_HEADER = "content-id";
    private static final String START_PARAM = "start";
    private static final String TYPE_PARAM = "type";
    private static final String BOUNDARY_PARAM = "boundary";

    protected Map<String, String> accepts;

    public String[] getAccepts(RequestContext request) {
        Collection<String> acceptKeys = getAlternateAccepts(request).keySet();
        return acceptKeys.toArray(new String[acceptKeys.size()]);
    }

    protected List<MultipartRelatedPost> getMultipartRelatedData(
            RequestContext request) throws IOException, ParseException,
            MessagingException {

        List<MultipartRelatedPost> result = new LinkedList<MultipartRelatedPost>();
        MultipartInputStream stream = getMultipartStream(request);
        stream.skipBoundary();

        String start = request.getContentType().getParameter(START_PARAM);

        Document<Source> source = null;
        Map<String, String> entryHeaders = new HashMap<String, String>();
        InputStream data = null;
        Map<String, String> dataHeaders = new HashMap<String, String>();

        Map<String, String> headers = getHeaders(stream);

        // first part is required to be the feed or entry
        if (start == null
                || start.length() == 0
                || (headers.containsKey(CONTENT_ID_HEADER) && start
                        .equals(headers.get(CONTENT_ID_HEADER)))
                || (headers.containsKey(CONTENT_TYPE_HEADER) && MimeTypeHelper
                        .isAtom(headers.get(CONTENT_TYPE_HEADER)))) {
            source = getEntry(stream, request);
            entryHeaders.putAll(headers);
        } else {
            throw new ParseException("First part was not a feed or entry: "
                    + headers);
            // data = getDataInputStream(multipart);
            // dataHeaders.putAll(headers);
        }

        try {
            while (stream.available() > 0) {
                stream.skipBoundary();
                headers = getHeaders(stream);
                if (start != null
                        && (headers.containsKey(CONTENT_ID_HEADER) && start
                                .equals(headers.get(CONTENT_ID_HEADER)))
                        && (headers.containsKey(CONTENT_TYPE_HEADER) && MimeTypeHelper
                                .isAtom(headers.get(CONTENT_TYPE_HEADER)))) {
                    throw new ParseException(
                            "Should not have found a second feed or entry: "
                                    + headers);
                } else {
                    data = getDataInputStream(stream);
                    dataHeaders.putAll(headers);
                }
                checkMultipartContent(source, dataHeaders, request);
                result.add( new MultipartRelatedPost(source, data, entryHeaders, dataHeaders) );
            }
        } catch (IOException ioe) {
            log.error("Unexpected error parsing multipart data", ioe);
        }
        return result;
    }

    private MultipartInputStream getMultipartStream(RequestContext request)
            throws IOException, ParseException, IllegalArgumentException {
        String boundary = request.getContentType().getParameter(BOUNDARY_PARAM);

        if (boundary == null) {
            throw new IllegalArgumentException(
                    "multipart/related stream invalid, boundary parameter is missing.");
        }

        boundary = "--" + boundary;

        String type = request.getContentType().getParameter(TYPE_PARAM);
        if (!(type != null && MimeTypeHelper.isAtom(type))) {
            throw new ParseException(
                    "multipart/related stream invalid, type parameter should be "
                            + Constants.ATOM_MEDIA_TYPE);
        }

        PushbackInputStream pushBackInput = new PushbackInputStream(
                request.getInputStream(), 2);
        pushBackInput.unread("\r\n".getBytes());

        return new MultipartInputStream(pushBackInput, boundary.getBytes());
    }

    private void checkMultipartContent(Document<Source> entry,
            Map<String, String> dataHeaders, RequestContext request)
            throws ParseException {
        if (entry == null) {
            throw new ParseException(
                    "multipart/related stream invalid, media link entry is missing");
        }
        if (!dataHeaders.containsKey(CONTENT_TYPE_HEADER)) {
            throw new ParseException(
                    "multipart/related stream invalid, data content-type is missing");
        }
        if (!isContentTypeAccepted(dataHeaders.get(CONTENT_TYPE_HEADER),
                request)) {
            throw new ParseException(
                    "multipart/related stream invalid, content-type "
                            + dataHeaders.get(CONTENT_TYPE_HEADER)
                            + " not accepted into this multipart file");
        }
    }

    private Map<String, String> getHeaders(MultipartInputStream multipart)
            throws IOException, MessagingException {
        Map<String, String> mapHeaders = new HashMap<String, String>();
        moveToHeaders(multipart);
        InternetHeaders headers = new InternetHeaders(multipart);

        Enumeration<Header> allHeaders = headers.getAllHeaders();
        if (allHeaders != null) {
            while (allHeaders.hasMoreElements()) {
                Header header = allHeaders.nextElement();
                mapHeaders.put(header.getName().toLowerCase(),
                        header.getValue());
            }
        }

        return mapHeaders;
    }

    private boolean moveToHeaders(InputStream stream) throws IOException {
        boolean dash = false;
        boolean cr = false;
        int byteReaded;

        while ((byteReaded = stream.read()) != -1) {
            switch (byteReaded) {
            case '\r':
                cr = true;
                dash = false;
                break;
            case '\n':
                if (cr == true)
                    return true;
                dash = false;
                break;
            case '-':
                if (dash == true) { // two dashes
                    stream.close();
                    return false;
                }
                dash = true;
                cr = false;
                break;
            default:
                dash = false;
                cr = false;
            }
        }
        return false;
    }

    private InputStream getDataInputStream(InputStream stream)
            throws IOException {
        Base64 base64 = new Base64();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();

        byte[] buffer = new byte[1024];
        while (stream.read(buffer) != -1) {
            bo.write(buffer);
        }
        return new ByteArrayInputStream(base64.decode(bo.toByteArray()));
    }

    private <T extends Element> Document<T> getEntry(InputStream stream,
            RequestContext request) throws ParseException, IOException {
        Parser parser = request.getAbdera().getParser();
        if (parser == null)
            throw new IllegalArgumentException(
                    "No Parser implementation was provided");
        Document<?> document = parser.parse(stream, request.getResolvedUri()
                .toString(), parser.getDefaultParserOptions());
        return (Document<T>) document;
    }

    private boolean isContentTypeAccepted(String contentType,
            RequestContext request) {
        if (getAlternateAccepts(request) == null) {
            return false;
        }
        for (Map.Entry<String, String> accept : getAlternateAccepts(request)
                .entrySet()) {
            if (accept.getKey().equalsIgnoreCase(contentType)
                    && accept.getValue() != null
                    && accept.getValue().equalsIgnoreCase(
                            Constants.LN_ALTERNATE_MULTIPART_RELATED)) {
                return true;
            }
        }
        return false;
    }

    protected class MultipartRelatedPost {
        private final Document<Source> source;
        private final InputStream data;
        private final Map<String, String> entryHeaders;
        private final Map<String, String> dataHeaders;

        public MultipartRelatedPost(Document<Source> base, InputStream data,
                Map<String, String> entryHeaders,
                Map<String, String> dataHeaders) {
            this.source = base;
            this.data = data;
            this.entryHeaders = entryHeaders;
            this.dataHeaders = dataHeaders;
        }

        public Document<Source> getSource() {
            return source;
        }

        public InputStream getData() {
            return data;
        }

        public Map<String, String> getEntryHeaders() {
            return entryHeaders;
        }

        public Map<String, String> getDataHeaders() {
            return dataHeaders;
        }
    }

    private final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(this
            .getClass());

}
