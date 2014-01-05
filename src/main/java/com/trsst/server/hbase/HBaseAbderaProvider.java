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
package com.trsst.server.hbase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.trsst.server.AbderaProvider;
import com.trsst.server.Storage;

/** Same as {@link AbderaProvider} but uses HBase as the {@link Storage} */
public class HBaseAbderaProvider extends AbderaProvider {

	private final Logger log = LoggerFactory.getLogger(HBaseAbderaProvider.class);

	/** Important to be 'volatile' for thread safety */
	private volatile HBaseStorage storage;

	public HBaseAbderaProvider() {
	}

	/**
	 * Returns {@link HBaseStorage} for persistence.
	 * 
	 * @param feedId
	 *            Ignored in this implementation.
	 * @return a HBaseStorage implementation
	 */
	@Override
	protected Storage getStorageForFeedId(String feedId) {
		// Double locking singleton, which makes this threadsafe and without
		// penalty due to 'synchronized' block thanks to the 'helper' variable
		HBaseStorage helper = storage;
		if (helper == null) {
			synchronized (this) {
				helper = storage;
				if (helper == null) {
					log.info("Creating a new HBaseStorage provider");
					storage = helper = new HBaseStorage();
				}
			}
		}
		return helper;
	}
}
