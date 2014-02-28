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

import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import com.trsst.Command;
import com.trsst.client.Client;
import com.trsst.server.Server;

/** Exposes client interface to javascript. */
public class AppClient {

    private Server server;
    private Client client;

    public AppClient() {
        // start a client with a local server
        try {
            // use "feed" path for atompub calls
             server = new Server(8181, "feed", false)  { // dev only 
            //server = new Server("feed", true) { // secure
                // serve static files and api from root
                protected void configureContext(ServletContextHandler context) {
                    super.configureContext(context);
                    context.addServlet(new ServletHolder(new AppServlet(
                            AppClient.this)), "/*");
                }
            };
            client = new Client(getServer().getServiceURL());
            log.error("Starting temporary service at: "
                    + getServer().getServiceURL());
        } catch (Exception e) {
            log.error("Could not start server for app client", e);
            throw new RuntimeException(
                    "Unexpected error starting app client: ", e);
        }
    }

    /**
     * @return the server
     */
    public Server getServer() {
        return server;
    }

    /**
     * @return the client
     */
    public Client getClient() {
        return client;
    }

    private final static org.slf4j.Logger log = org.slf4j.LoggerFactory
            .getLogger(Command.class);

}
