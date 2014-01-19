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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.URLConnection;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import com.trsst.Common;

/**
 * Dumb file persistence for small nodes and test cases. This will get you up
 * and running quickly, but you'll want to replace it with an implementation
 * backed by some kind of database or document store.
 * 
 * Files are placed in a "trsstd" directory inside of your home directory, or
 * inside the directory in the system property "com.trsst.storage" if specified.
 * 
 * @author mpowers
 */
public class FileStorage implements Storage {

    public static final String FEED_XML = "feed.xml";
    public static final String ENTRY_SUFFIX = ".atom";
    public static final String ENCODING = "UTF-8";

    private File root;

    public FileStorage() {
        root = getRoot();
        System.err.println("Default file persistence serving from: " + root);
    }

    public static File getRoot() {
        String path = System.getProperty("com.trsst.server.storage");
        if (path != null) {
            try {
                return new File(path);
            } catch (Throwable t) {
                System.err.println("Invalid path: " + path + " : "
                        + t.getMessage());
            }
        }
        path = System.getProperty("user.home", ".");
        return new File(path, "trsstd");
    }

    public int getEntryCountForFeedId(String feedId, Date after, Date before,
            String query, String[] mentions, String[] tags, String verb) {
        File[] files = new File(root, feedId).listFiles(new FileFilter() {
            public boolean accept(File file) {
                return file.getName().toLowerCase().endsWith(ENTRY_SUFFIX);
            }
        });
        return files.length;
    }

    public String[] getFeedIds(int start, int length) {
        File[] files = root.listFiles();
        List<String> result = new LinkedList<String>();
        for (File f : files) {
            if (f.isDirectory()) {
                if (new File(f, FEED_XML).exists()) {
                    result.add(Common.unescapeHTML(f.getName()));
                }
            }
        }
        return result.toArray(new String[0]);
    }

    public String[] getCategories(int start, int length) {

        // TODO: implement category trackers

        return new String[0];
    }

    public long[] getEntryIdsForFeedId(String feedId, int start, int length,
            Date after, Date before, String query, String[] mentions,
            String[] tags, String verb) {
        if (start < 0 || length < 1) {
            throw new IllegalArgumentException("Invalid range: start: " + start
                    + " : length: " + length);
        }
        long[] all = getEntryIdsForFeedId(feedId, after, before);
        if (start >= all.length) {
            return new long[0];
        }

        // TODO: implement query/tag/mention/verb filter

        int end = Math.min(start + length, all.length);
        long[] result = new long[end - start];
        System.arraycopy(all, start, result, 0, result.length);
        return result;
    }

    public String readFeed(String feedId) throws FileNotFoundException,
            IOException {
        return readStringFromFile(getFeedFileForFeedId(feedId));
    }

    public void updateFeed(String feedId, Date lastUpdated, String feed)
            throws FileNotFoundException, IOException {
        File file = getFeedFileForFeedId(feedId);
        writeStringToFile(feed, file);
        if (lastUpdated != null) {
            file.setLastModified(lastUpdated.getTime());
        }
    }

    public String readEntry(String feedId, long entryId)
            throws FileNotFoundException, IOException {
        return readStringFromFile(getEntryFileForFeedEntry(feedId, entryId));
    }

    public void updateEntry(String feedId, long entryId, Date publishDate,
            String entry) throws IOException {
        File file = getEntryFileForFeedEntry(feedId, entryId);
        writeStringToFile(entry, file);
        if (publishDate != null) {
            file.setLastModified(publishDate.getTime());
        }
    }

    public void deleteEntry(String feedId, long entryId) throws IOException {
        File file = getEntryFileForFeedEntry(feedId, entryId);
        if (file.exists()) {
            file.delete();
        }
    }

    public String readFeedEntryResourceType(String feedId, long entryId,
            String resourceId) throws IOException {
        return getMimeTypeForFile(getResourceFileForFeedEntry(feedId, entryId,
                resourceId));
    }

    public InputStream readFeedEntryResource(String feedId, long entryId,
            String resourceId) throws IOException {
        return new BufferedInputStream(new FileInputStream(
                getResourceFileForFeedEntry(feedId, entryId, resourceId)));
    }

    public void updateFeedEntryResource(String feedId, long entryId,
            String resourceId, String mimetype, Date publishDate, byte[] data)
            throws IOException {
        File file = getResourceFileForFeedEntry(feedId, entryId, resourceId);
        OutputStream output = new BufferedOutputStream(new FileOutputStream(
                file));
        try {
            output.write(data, 0, data.length);
            output.flush();
            System.err.println("wrote: " + file.getAbsolutePath());
        } finally {
            try {
                output.close();
            } catch (IOException ioe) {
                // suppress any futher error on closing
            }
        }
        if (publishDate != null) {
            file.setLastModified(publishDate.getTime());
        }
    }

    public void deleteFeedEntryResource(String feedId, long entryId,
            String resourceId) {
        File file = getResourceFileForFeedEntry(feedId, entryId, resourceId);
        if (file.exists()) {
            file.delete();
        }
    }

    private static final String getMimeTypeForFile(File file) {
        return URLConnection.getFileNameMap().getContentTypeFor(file.getName());
    }

    private long[] getEntryIdsForFeedId(String feedId, Date after, Date before) {
        feedId = Common.encodeURL(feedId);
        final long afterTime = after != null ? after.getTime() : 0;
        final long beforeTime = before != null ? before.getTime() : 0;
        File[] files = new File(root, feedId).listFiles(new FileFilter() {
            public boolean accept(File file) {
                if ((file.getName().toLowerCase().endsWith(ENTRY_SUFFIX))
                        && (afterTime == 0 || file.lastModified() > afterTime)
                        && (beforeTime == 0 || file.lastModified() < beforeTime)) {
                    return true;
                }
                return false;
            }
        });
        Arrays.sort(files, new Comparator<File>() {
            public int compare(File o1, File o2) {
                // System.out.println( new Date(o1.lastModified() ) + " : " +
                // new Date(o2.lastModified()) );
                return o1.lastModified() > o2.lastModified() ? -1 : o1
                        .lastModified() < o2.lastModified() ? 1 : 0;
            }
        });
        String name;
        int suffix = ENTRY_SUFFIX.length();
        long[] result = new long[files.length];
        for (int i = 0; i < files.length; i++) {
            name = files[i].getName();
            result[i] = Long.parseLong(
                    name.substring(0, name.length() - suffix), 16);
        }
        return result;
    }

    private static final String readStringFromFile(File file)
            throws IOException {
        StringBuffer result = new StringBuffer();
        Reader reader = null;
        try {
            reader = new InputStreamReader(new BufferedInputStream(
                    new FileInputStream(file)), ENCODING);
            int c;
            char[] buf = new char[256];
            while ((c = reader.read(buf)) > 0) {
                result.append(buf, 0, c);
            }
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (IOException ioe) {
                // suppress any futher error on closing
            }
        }
        return result.toString();
    }

    private static final void writeStringToFile(String text, File file)
            throws IOException {
        Writer writer = null;
        try {
            if (!file.getParentFile().exists()) {
                file.getParentFile().mkdirs(); // ensure directory exists
            }
            writer = new OutputStreamWriter(new BufferedOutputStream(
                    new FileOutputStream(file)), ENCODING);
            writer.write(text);
            writer.flush();
            System.err.println("wrote: " + file.getAbsolutePath());
        } finally {
            try {
                if (writer != null) {
                    writer.close();
                }
            } catch (IOException ioe) {
                // suppress any futher error on closing
            }
        }
    }

    public static File getFeedFileForFeedId(String feedId) {
        feedId = Common.encodeURL(feedId);
        return new File(new File(getRoot(), feedId), FEED_XML);
    }

    public static File getEntryFileForFeedEntry(String feedId, long entryId) {
        feedId = Common.encodeURL(feedId);
        return new File(new File(getRoot(), feedId), Long.toHexString(entryId)
                + ENTRY_SUFFIX);
    }

    public static File getResourceFileForFeedEntry(String feedId, long entryId,
            String resourceid) {
        feedId = Common.encodeURL(feedId);
        return new File(new File(getRoot(), feedId), Long.toHexString(entryId)
                + '-' + resourceid);
    }

}