/*
 * Copyright 2014 acuga
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
import java.io.InputStream;
import java.util.Date;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.HConnection;
import org.apache.hadoop.hbase.client.HConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HBaseStorage implements Storage {
	
	private static final Logger log = LoggerFactory.getLogger(HBaseStorage.class);
	
	/** HBase configuration object */
	private static final Configuration configuration = HBaseConfiguration.create();

	/** HBase connection object */
	private static HConnection connection;

	/**
	 * Sets the hostname of the HBase server (zookeeper cluster) for connecting
	 * to. Default is "localhost" if nothing is set.
	 */
	public static void setZookeeperHostName(String hostname) {
		configuration.set("hbase.zookeeper.quorum", hostname);
	}

	/**
	 * Sets the port of the HBase server (zookeeper cluster) for connecting to.
	 * Default is 2181 if nothing is set.
	 */
	public static void setZookeeperPort(String port) {
		configuration.set("hbase.zookeeper.property.clientPort", port);
	}

	/**
	 * Opens HBase connection according to whatever configuration is defined at
	 * this point. Expects all connection settings to be configured prior to
	 * being invoked.
	 * 
	 * @throws IOException
	 *             If there is trouble connecting to HBase
	 */
	public static void connect() throws IOException {
		log.info("Initiating connection to HBase cluster");
		connection = HConnectionManager.createConnection(configuration);
	}

	/**
	 * Closes the HBase connection and releases any resources associated with
	 * such.
	 * 
	 * @throws IOException
	 *             If a problem is encountered while closing the HBase
	 *             connection
	 */
	public static void close() throws IOException {
		log.info("Closing connection to HBase cluster");
		connection.close();
	}

	public String[] getFeedIds(int start, int length) {
		// TODO Auto-generated method stub
		return null;
	}

	public String[] getCategories(int start, int length) {
		// TODO Auto-generated method stub
		return null;
	}

	public int getEntryCountForFeedId(String feedId) {
		// TODO Auto-generated method stub
		return 0;
	}

	public long[] getEntryIdsForFeedId(String feedId, int start, int length, Date after, Date before, String query,
			String[] mentions, String[] tags, String verb) {
		// TODO Auto-generated method stub
		return null;
	}

	public String readFeed(String feedId) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	public void updateFeed(String feedId, Date lastUpdated, String feed) throws IOException {
		// TODO Auto-generated method stub

	}

	public String readEntry(String feedId, long entryId) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	public void updateEntry(String feedId, long entryId, Date publishDate, String entry) throws IOException {
		// TODO Auto-generated method stub

	}

	public void deleteEntry(String feedId, long entryId) throws IOException {
		// TODO Auto-generated method stub

	}

	public String readFeedEntryResourceType(String feedId, long entryId, String resourceId) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	public InputStream readFeedEntryResource(String feedId, long entryId, String resourceId) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	public void updateFeedEntryResource(String feedId, long entryId, String resourceId, String mimeType,
			Date publishDate, InputStream data) throws IOException {
		// TODO Auto-generated method stub

	}

	public void deleteFeedEntryResource(String feedId, long entryId, String resourceId) throws IOException {
		// TODO Auto-generated method stub

	}

}
