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
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.URL;
import java.net.UnknownHostException;

import org.apache.abdera.protocol.server.servlet.AbderaServlet;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

/**
 * Jetty-specific configuration to host an Abdera servlet that is configured to
 * serve the Trsst protocol.
 * 
 * @author mpowers
 */
public class Server {
    private final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(this
            .getClass());

    int port;
    String path;
    org.eclipse.jetty.server.Server server;

    public Server() throws Exception {
        this(0, null);
    }

    public Server(int port) throws Exception {
        this(port, null);
    }

    public Server(int port, String path) throws Exception {
        try {
            if (path != null) {
                if (path.endsWith("/")) {
                    path = path.substring(0, path.length() - 1);
                }
                if (!path.startsWith("/")) {
                    path = "/" + path;
                }
            } else {
                path = "";
            }
            if (port == 0) {
                port = allocatePort();
            }
            server = new org.eclipse.jetty.server.Server(port);

            ServletContextHandler context = new ServletContextHandler(
                    ServletContextHandler.SESSIONS);
            context.setContextPath("/");
            server.setHandler(context);

            ServletHolder servletHolder = new ServletHolder(new AbderaServlet());
            servletHolder.setInitParameter(
                    "org.apache.abdera.protocol.server.Provider",
                    "com.trsst.server.AbderaProvider");
            context.addServlet(servletHolder, path + "/*");
            this.port = port;
            this.path = path;

            HttpConfiguration http_config = new HttpConfiguration();
            ServerConnector http = new ServerConnector(server,
                    new HttpConnectionFactory(http_config));
            http.setPort(port);
            http.setIdleTimeout(500000);

            server.setConnectors(new Connector[] { http });
            server.start();

        } catch (Exception ioe) {
            log.error("could not start server on " + port + " : " + path, ioe);
            throw ioe;
        }
    }

    /**
     * Grabs a new server port. Borrowed from axiom.
     */
    private int allocatePort() {
        try {
            ServerSocket ss = new ServerSocket(0);
            int port = ss.getLocalPort();
            ss.close();
            return port;
        } catch (IOException ex) {
            log.error("Unable to allocate TCP port; defaulting to 54445.", ex);
            return 54445; // arbitrary port
        }
    }

    public URL getServiceURL() {
        URL result = null;
        try {
            result = new URL("http", "localhost", port, path); // default
            result = new URL("http", InetAddress.getLocalHost()
                    .getHostAddress(), port, path);
        } catch (MalformedURLException e) {
            // accept default
        } catch (UnknownHostException e) {
            // accept default
        }
        return result;
    }

    public void stop() {
        try {
            server.stop();
        } catch (Exception e) {
            log.error("Error while stopping server", e);
        }
        server.destroy();
    }
}