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
package com.trsst.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.tika.Tika;

import com.trsst.Command;
import com.trsst.Common;

/**
 * Private servlet for our private client. Exposes command-line client as a
 * servlet for "post" command only. Parameters "attach" and "home" are rejected.
 * Non-local clients are rejected.
 */
public class AppServlet extends HttpServlet {

    private static final long serialVersionUID = -8082699767921771750L;
    private static final String[] ALLOWED_FILES = { "boiler.css", "index.html",
            "favicon.ico", "jquery-1.10.1.js", "model.js", "composer.js",
            "pollster.js", "controller.js", "ui.css", "icon-256.png",
            "icon-back.png", "note.svg", "note.png", "loading-on-white.gif",
            "loading-on-gray.gif", "loading-on-orange.gif", "icon-rss.png" };

    private final Map<String, byte[]> resources;
    private AppClient client;

    public AppServlet(AppClient client) {
        this.client = client;

        // load static resources
        resources = new HashMap<String, byte[]>();
        for (String s : ALLOWED_FILES) {
            try {
                resources.put(
                        s,
                        Common.readFully(this.getClass().getResourceAsStream(
                                "site/" + s)));
            } catch (Exception e) {
                log.error("Could not load static http resource: " + s, e);
            }
        }
    }

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        doPost(request, response);
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        // FLAG: limit access only to local clients
        if (!request.getRemoteAddr().equals(request.getLocalAddr())) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "Non-local clients are not allowed.");
            return;
        }

        // in case of any posted files
        InputStream inStream = null;

        // determine if supported command: pull, push, post
        String path = request.getPathInfo();
        System.err.println(new Date().toString() + " " + path);
        if (path != null) {
            // FLAG: limit only to pull and post
            if (path.startsWith("/pull/") || path.startsWith("/post")) {
                // FLAG: we're sending the user's keystore
                // password over the wire (over SSL)
                List<String> args = new LinkedList<String>();
                if (path.startsWith("/pull/")) {
                    path = path.substring("/pull/".length());
                    response.setContentType("application/atom+xml; type=feed; charset=utf-8");
                    //System.out.println("doPull: " + request.getParameterMap());
                    args.add("pull");
                    if (request.getParameterMap().size() > 0) {
                        boolean first = true;
                        for (Object name : request.getParameterMap().keySet()) {
                            // FLAG: don't allow "home" (server-abuse)
                            // FLAG: don't allow "attach" (file-system access)
                            if ("decrypt".equals(name) || "pass".equals(name)) {
                                for (String value : request
                                        .getParameterValues(name.toString())) {
                                    args.add("--" + name.toString());
                                    args.add(value);
                                }
                            } else {
                                for (String value : request
                                        .getParameterValues(name.toString())) {
                                    if (first) {
                                        path = path + '?';
                                        first = false;
                                    } else {
                                        path = path + '&';
                                    }
                                    path = path + name + '=' + value;
                                }
                            }
                        }
                    }
                    args.add(path);

                } else if (path.startsWith("/post")) {
                    //System.out.println("doPost: " + request.getParameterMap());
                    args.add("post");

                    try { // h/t http://stackoverflow.com/questions/2422468
                        List<FileItem> items = new ServletFileUpload(
                                new DiskFileItemFactory())
                                .parseRequest(request);
                        for (FileItem item : items) {
                            if (item.isFormField()) {
                                // process regular form field
                                String name = item.getFieldName();
                                String value = item.getString().trim();
//                                System.out.println("AppServlet: " + name
//                                        + " : " + value);
                                if (value.length() > 0) {
                                    // FLAG: don't allow "home" (server-abuse)
                                    // FLAG: don't allow "attach" (file-system
                                    // access)
                                    if ("id".equals(name)) {
                                        if (value.startsWith("urn:feed:")) {
                                            value = value.substring("urn:feed:"
                                                    .length());
                                        }
                                        args.add(value);
                                    } else if (!"home".equals(name)
                                            && !"attach".equals(name)) {
                                        args.add("--" + name);
                                        args.add(value);
                                    }
                                } else {
                                    log.debug("Empty form value for name: "
                                            + name);
                                }
                            } else if (item.getSize() > 0) {
                                // process form file field (input type="file").
                                // String filename = FilenameUtils.getName(item
                                // .getName());
                                if (item.getSize() > 1024 * 1024 * 10) {
                                    throw new FileUploadException(
                                            "Current maximum upload size is 10MB");
                                }
                                String name = item.getFieldName();
                                if ("icon".equals(name) || "logo".equals(name)) {
                                    args.add("--" + name);
                                    args.add("-");
                                }
                                inStream = item.getInputStream();
                                // NOTE: only handles one file!
                            } else {
                                log.debug("Ignored form field: "
                                        + item.getFieldName());
                            }
                        }
                    } catch (FileUploadException e) {
                        response.sendError(HttpServletResponse.SC_BAD_REQUEST,
                                "Could not parse multipart request: " + e);
                        return;
                    }
                }
                // home is always our private local server
                args.add("--host");
                args.add(client.getServer().getServiceURL().toString());

                // send post data if any to command input stream
                if (inStream != null) {
                    args.add("--attach");
                }
                //System.out.println(args);

                PrintStream outStream = new PrintStream(
                        response.getOutputStream());
                int result = new Command().doBegin(args.toArray(new String[0]),
                        outStream, inStream);
                if (result != 0) {
                    response.sendError(
                            HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                            "Internal error code: " + result);
                } else {
                    outStream.flush();
                }
                return;
            }

            // otherwise: determine if static resource request
            if (path.startsWith("/")) {
                path = path.substring(1);
            }

            byte[] result = resources.get(path);
            String mimetype = null;
            if (result == null) {
                // if ("".equals(path) || path.endsWith(".html")) {
                // treat all html requests with index doc
                result = resources.get("index.html");
                mimetype = "text/html";
                // }
            }
            if (result != null) {
                if (mimetype == null) {
                    if (path.endsWith(".html")) {
                        mimetype = "text/html";
                    } else if (path.endsWith(".css")) {
                        mimetype = "text/css";
                    } else if (path.endsWith(".js")) {
                        mimetype = "application/javascript";
                    } else if (path.endsWith(".png")) {
                        mimetype = "image/png";
                    } else if (path.endsWith(".jpg")) {
                        mimetype = "image/jpeg";
                    } else if (path.endsWith(".jpeg")) {
                        mimetype = "image/jpeg";
                    } else if (path.endsWith(".gif")) {
                        mimetype = "image/gif";
                    } else {
                        mimetype = new Tika().detect(result);
                    }
                }
                if (request.getHeader("If-None-Match:") != null) {
                    // client should always use cached version
                    log.info("sending 304");
                    response.setStatus(304); // Not Modified
                    return;
                }
                // otherwise allow ETag/If-None-Match
                response.setHeader("ETag", Long.toHexString(path.hashCode()));
                if (mimetype != null) {
                    response.setContentType(mimetype);
                }
                response.setContentLength(result.length);
                response.getOutputStream().write(result);
                return;
            }

        }

        // // otherwise: 404 Not Found
        // response.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    private final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(this
            .getClass());

}
