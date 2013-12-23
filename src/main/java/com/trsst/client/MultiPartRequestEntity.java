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
package com.trsst.client;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import org.apache.abdera.model.Base;
import org.apache.abdera.util.MimeTypeHelper;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.httpclient.methods.RequestEntity;

/**
 * A RequestEntity that handles an Entry or a Feed and supports multiple
 * attachments. Per convention, each content id should match an id referenced
 * in an entry with a corresponding digest or the server must reject.
 * 
 * @author mpowers
 */
public class MultiPartRequestEntity implements RequestEntity {

    private final Base base;
    private final byte[][] content;
    private final String[] contentId;
    private final String[] contentType;
    private String boundary;

    public MultiPartRequestEntity(Base base, byte[][] content,
            String[] contentId, String[] contentType) {
        this.base = base;
        this.content = content;
        this.contentId = contentId;
        this.contentType = contentType;
        this.boundary = boundary != null ? boundary : String.valueOf(System
                .currentTimeMillis());
    }

    public void writeRequest(OutputStream arg0) throws IOException {
        DataOutputStream out = new DataOutputStream(arg0);
        out.writeBytes("--" + boundary + "\r\n");
        writeEntry(base, out);
        out.writeBytes("--" + boundary + "\r\n");
        if (content != null) {
            for (int i = 0; i < content.length; i++) {
                writeContent(content[i], contentId[i], contentType[i], out);
                out.writeBytes("\r\n" + "--" + boundary + "--");
            }
        }
    }

    private static void writeEntry(Base base, DataOutputStream out)
            throws IOException {
        out.writeBytes("content-type: " + MimeTypeHelper.getMimeType(base)
                + "\r\n\r\n");
        base.writeTo(out);
    }

    private static void writeContent(byte[] content, String contentId,
            String contentType, DataOutputStream out) throws IOException {
        if (contentType == null) {
            throw new NullPointerException("media content type can't be null");
        }
        out.writeBytes("content-type: " + contentType + "\r\n");
        out.writeBytes("content-id: <cid:" + contentId + ">\r\n\r\n");
        out.write(new Base64().encode(content));
    }

    public long getContentLength() {
        return -1;
    }

    public String getContentType() {
        return "Multipart/Related; boundary=\"" + boundary + "\";type=\""
                + MimeTypeHelper.getMimeType(base) + "\"";
    }

    public boolean isRepeatable() {
        return true;
    }
}
