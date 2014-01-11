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
import org.mortbay.jetty.servlet.Context;
import org.mortbay.jetty.servlet.ServletHolder;

/**
 * Jetty-specific configuration to host an Abdera servlet that is configured to
 * serve the Trsst protocol.
 * 
 * @author mpowers
 */
public class Server {
	private final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(this.getClass());

	int port;
	String path;
	org.mortbay.jetty.Server server;

	private boolean isUsingHBase = false;

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
			server = new org.mortbay.jetty.Server(port);
			Context context = new Context(server, "/", Context.SESSIONS);
			ServletHolder servletHolder = new ServletHolder(new AbderaServlet());
			String provider = detectProviderForEnvironment();
			servletHolder.setInitParameter("org.apache.abdera.protocol.server.Provider", provider);
			context.addServlet(servletHolder, path + "/*");
			this.port = port;
			this.path = path;
			server.start();
		} catch (Exception ioe) {
			log.error("could not start server on " + port + " : " + path, ioe);
			throw ioe;
		}
	}

	private String detectProviderForEnvironment() {
		String storageProvider = System.getProperty("com.trsst.storage.provider");
		if (storageProvider != null) {
			storageProvider = storageProvider.trim().toLowerCase();
			if (storageProvider.equals("hbase")) {
				isUsingHBase = true;
				initHBaseConnectionFromEnvironment();
				return "com.trsst.server.HBaseAbderaProvider";
			}
		}

		// Default AbderaProvider
		return "com.trsst.server.AbderaProvider";
	}

	/**
	 * Initializes the provider based on the environment variables, if present.
	 * Opens connection to HBase.
	 * 
	 * <pre>
	 * com.trsst.hbase.hostname = host name of HBase server to use
	 * com.trsst.hbase.port = port of HBase server to use
	 * </pre>
	 * 
	 * If either variables are not present, they're ignored individually and the
	 * default for that value is used.
	 * 
	 * <pre>
	 * default hostname = localhost
	 * default port = 2181
	 * </pre>
	 */
	private void initHBaseConnectionFromEnvironment() {
		log.info("Configuring HBase connection from environment");
		String hostName = System.getProperty("com.trsst.hbase.hostname");
		hostName = hostName == null ? "" : hostName.trim().toLowerCase();
		if (hostName.length() > 0) {
			HBaseStorage.setZookeeperHostName(hostName);
			log.info("Using HBase Zookeeper hostname: " + hostName);
		}

		String port = System.getProperty("com.trsst.hbase.port");
		port = port == null ? "" : port.trim().toLowerCase();
		if (port.length() > 0) {
			HBaseStorage.setZookeeperPort(port);
			log.info("Using HBase Zookeeper port: " + port);
		} 

		try {
			HBaseStorage.connect();	
		} catch(IOException e) {
			log.error("Error opening HBase connection: " + e.getMessage(), e);
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
			result = new URL("http", InetAddress.getLocalHost().getHostAddress(), port, path);
		} catch (MalformedURLException e) {
			// accept default
		} catch (UnknownHostException e) {
			// accept default
		}
		return result;
	}

	/** Shuts down the server and closes any open database connections */
	public void stop() {
		if (isUsingHBase) {
			try {
				HBaseStorage.close();	
			} catch(IOException e) {
				log.warn("Trouble closing HBase connection: " + e.getMessage(), e);
			}
		}

		try {
			server.stop();
		} catch (Exception e) {
			log.error("Error while stopping server", e);
		}
		server.destroy();
	}
}