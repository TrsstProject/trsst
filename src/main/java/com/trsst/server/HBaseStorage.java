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

import static org.apache.hadoop.hbase.util.Bytes.toBytes;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HConnection;
import org.apache.hadoop.hbase.client.HConnectionManager;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.util.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HBaseStorage implements Storage {

	/**
	 * Best to only use one column family and to keep column family names as
	 * small as possible, preferably one character.
	 * 
	 * See sections 6.2 and 6.3 of <a
	 * href="http://hbase.apache.org/book.html#schema"
	 * >http://hbase.apache.org/book.html#schema</a>
	 */
	private static final byte[] COLUMN_FAMILY = toBytes("d");

	private static final byte[] FEED_TABLE = toBytes("feed");
	private static final byte[] FEED_COLUMN_XML = toBytes("xml");
	// TODO: Should we add a 'count' column for the number of entries,
	// (incremented, decremented each time an entry is written)

	private static final byte[] ENTRY_TABLE = toBytes("entry");
	private static final byte[] ENTRY_COLUMN_XML = toBytes("xml");

	/** Common name for common field across tables */
	private static final byte[] COLUMN_DATE_UPDATED = toBytes("updated");

	/** HBase configuration object */
	private static final Configuration configuration = HBaseConfiguration.create();

	/** HBase connection object */
	private static HConnection connection;

	/** Logger */
	private static final Logger log = LoggerFactory.getLogger(HBaseStorage.class);

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
		initSchema();
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

	/**
	 * Initializes the database schema if necessary.
	 * 
	 * @throws IOException
	 */
	private static void initSchema() throws IOException {
		HBaseAdmin admin = null;
		try {
			admin = new HBaseAdmin(configuration);
			HTableDescriptor[] existingTables = admin.listTables(Bytes.toString(FEED_TABLE));
			if (existingTables.length == 0) {
				createTable(admin, FEED_TABLE);
			}
			existingTables = admin.listTables(Bytes.toString(FEED_TABLE));
			if (existingTables.length == 0) {
				createTable(admin, ENTRY_TABLE);
			}
			// Later can add more tables here... (Categories,
			// SearchIndex...)
		} catch (IOException e) {
			log.error("Trouble creating feed schema.", e);
			throw e;
		} finally {
			if (admin != null) {
				admin.close();
			}
		}
	}

	/** Essentially reverses the feedId so that the row keys aren't montonic */
	protected static byte[] createFeedKey(String feedId) {
		return toBytes(StringUtils.reverse(feedId));
	}

	/** Composite of reversed feed id + entryId. Non-monotonic */
	protected static byte[] createEntryKeyString(String feedId, long entryId) {
		return toBytes(StringUtils.reverse(feedId) + Long.toString(entryId));
	}

	private static void createTable(HBaseAdmin admin, byte[] nameOfTable) throws IOException {
		HTableInterface iTable = null;
		try {
			iTable = connection.getTable(nameOfTable);
			TableName qualifiedTableName = iTable.getName();
			HTableDescriptor table = new HTableDescriptor(qualifiedTableName);
			admin.createTable(table);

			// Cannot edit a structure on an active table.
			admin.disableTable(qualifiedTableName);

			// Best to only use one ColumnFamily. See Section 6.2 of
			// http://hbase.apache.org/book.html#schema
			HColumnDescriptor baseColumnFamily = new HColumnDescriptor(COLUMN_FAMILY);
			admin.addColumn(qualifiedTableName, baseColumnFamily);

			// Done updating, re-enable the table
			admin.enableTable(qualifiedTableName);
		} catch (IOException e) {
			log.error("Trouble creating table with name: " + nameOfTable, e);
			throw e;
		} finally {
			if (iTable != null) {
				iTable.close();
			}
		}
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
		byte[] feedRowKey = createFeedKey(feedId);
		Get get = new Get(feedRowKey);
		get.addColumn(COLUMN_FAMILY, FEED_COLUMN_XML);
		
		HTableInterface table = connection.getTable(FEED_TABLE);
		Result result = table.get(get);
		table.close();
		//Cell [] cells = result.rawCells();
		return "";
	}

	public void updateFeed(String feedId, Date lastUpdated, String feed) throws IOException {
		long updated = lastUpdated == null ? System.currentTimeMillis() : lastUpdated.getTime();
		Put put = new Put(createFeedKey(feedId));
		put.add(COLUMN_FAMILY, FEED_COLUMN_XML, toBytes(feed));
		put.add(COLUMN_FAMILY, COLUMN_DATE_UPDATED, toBytes(updated));

		HTableInterface table = connection.getTable(FEED_TABLE);
		table.put(put);
		table.close();
	}

	public String readEntry(String feedId, long entryId) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	public void updateEntry(String feedId, long entryId, Date publishDate, String entry) throws IOException {
		long updated = publishDate == null ? System.currentTimeMillis() : publishDate.getTime();

		Put put = new Put(createEntryKeyString(feedId, entryId));
		put.add(COLUMN_FAMILY, ENTRY_COLUMN_XML, toBytes(entry));
		put.add(COLUMN_FAMILY, COLUMN_DATE_UPDATED, toBytes(updated));

		HTableInterface table = connection.getTable(FEED_TABLE);
		table.put(put);
		table.close();
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
		byte[] content = IOUtils.toByteArray(data);
		// JavaDocs indicate we must close this stream
		data.close(); 
		long updated = publishDate == null ? System.currentTimeMillis() : publishDate.getTime();

		Put put = new Put(createEntryKeyString(feedId, entryId));
		put.add(COLUMN_FAMILY, toBytes(resourceId), content);
		put.add(COLUMN_FAMILY, toBytes(resourceId + "_updated"), toBytes(updated));

		if (mimeType != null) {
			// Column name = resourceId_type
			put.add(COLUMN_FAMILY, toBytes(resourceId + "_type"), toBytes(mimeType));
		}

		HTableInterface table = connection.getTable(FEED_TABLE);
		table.put(put);
		table.close();
	}

	public void deleteFeedEntryResource(String feedId, long entryId, String resourceId) throws IOException {
		// TODO Auto-generated method stub
		
	}
}
