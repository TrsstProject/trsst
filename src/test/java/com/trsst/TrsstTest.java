package com.trsst;

import java.io.File;
import java.net.URL;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.LinkedList;
import java.util.List;

import javax.xml.namespace.QName;

import org.apache.abdera.model.Element;
import org.apache.abdera.model.Entry;
import org.apache.abdera.model.Feed;
import org.apache.commons.codec.binary.Base64;

import com.trsst.client.Client;
import com.trsst.server.FileStorage;
import com.trsst.server.Server;

import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

/**
 * Unit test for command-line functions.
 */
public class TrsstTest extends TestCase {
    /**
     * Create the test case
     * 
     * @param testName
     *            name of the test case
     */
    public TrsstTest(String testName) {
        super(testName);
    }

    /**
     * @return the suite of tests being tested
     */
    public static Test suite() {
        return new TestSuite(TrsstTest.class);
    }

    /**
     * Test the command line operations.
     */
    public void testApp() {
        try {
//             System.setProperty("org.slf4j.simpleLogger.defaultLogLevel",
//             "debug");
            

            Feed feed;
            Entry entry;
            List<String> idsToCleanup = new LinkedList<String>();
            List<File> entriesToCleanup = new LinkedList<File>();

            Server server = new Server();
            assertNotNull(server.getServiceURL());

            String feedId;
            Client client = new Client(server.getServiceURL());
            KeyPair signingKeys, encryptionKeys;
            PublicKey publicKey;
            Element signatureElement;
            String signatureValue;

            // generate account
            signingKeys = Common.generateSigningKeyPair();
            assertNotNull("Generating signing keys", signingKeys);
            encryptionKeys = Common.generateEncryptionKeyPair();
            assertNotNull("Generating encryption keys", encryptionKeys);
            feedId = Common.toFeedId(signingKeys.getPublic());
            idsToCleanup.add(feedId);

            // public key serialization
            publicKey = signingKeys.getPublic();
            assertTrue("Signing keys serialize", publicKey.equals(Common
                    .toPublicKey(Common.fromPublicKey(publicKey))));
            publicKey = encryptionKeys.getPublic();
            assertTrue("Encryption keys serialize", publicKey.equals(Common
                    .toPublicKey(Common.fromPublicKey(publicKey))));

            // generate feed with no entries
            feed = client.post(signingKeys, encryptionKeys.getPublic(), null,
                    null, null, null, null, null, null, null, null);
            assertNotNull("Generating empty feed", feed);
            assertEquals("Empty feed retains id", feed.getId().toString(),
                    feedId);
            assertEquals("Empty feed contains no entries", feed.getEntries()
                    .size(), 0);
            signatureElement = feed.getFirstChild(new QName(
                    "http://www.w3.org/2000/09/xmldsig#", "Signature"));
            assertNotNull("Feed has signature", signatureElement);

            // generate account
            signingKeys = Common.generateSigningKeyPair();
            assertNotNull("Generating signing keys", signingKeys);
            encryptionKeys = Common.generateEncryptionKeyPair();
            assertNotNull("Generating encryption keys", encryptionKeys);
            feedId = Common.toFeedId(signingKeys.getPublic());
            idsToCleanup.add(feedId);

            // generate feed with entry
            feed = client.post(signingKeys, encryptionKeys.getPublic(),
                    "First Post!");
            assertNotNull("Generating feed with entry", feed);
            assertEquals("Feed retains id", feedId, feed.getId().toString());
            assertEquals("Feed contains one entry", 1, feed.getEntries().size());
            signatureElement = feed.getFirstChild(new QName(
                    "http://www.w3.org/2000/09/xmldsig#", "Signature"));
            assertNotNull("Feed has signature", signatureElement);
            entry = feed.getEntries().get(0);
            entriesToCleanup.add(FileStorage.getEntryFileForFeedEntry(feedId,
                    entry.getId().toString()));
            assertEquals("Entry retains title", "First Post!", entry.getTitle());

            // encryption roundtrip
            String test = entry.toString();
            KeyPair b = Common.generateEncryptionKeyPair();
            byte[] bytes = Client.encryptElement(entry, b.getPublic());
            Element element = Client.decryptElement(bytes, b.getPrivate());
            assertEquals("Encryption round trip test", test, element.toString());

            // grab signature
            signatureElement = entry.getFirstChild(new QName(
                    "http://www.w3.org/2000/09/xmldsig#", "Signature"));
            assertNotNull("Entry has signature", signatureElement);
            signatureElement = signatureElement.getFirstChild(new QName(
                    "http://www.w3.org/2000/09/xmldsig#", "SignatureValue"));
            assertNotNull("Entry has signature value", signatureElement);

            // save for predecessor verification
            String predecessorId = entry.getId().toString();
            signatureValue = signatureElement.getText();

            // generate entry with full options
            feed = client.post(signingKeys, encryptionKeys.getPublic(),
                    "Second Post!", "post", null, "This is the body",
                    new String[] { idsToCleanup.iterator().next(), feedId },
                    new String[] { "fitter", "happier", "more productive" },
                    null, null, null);
            assertNotNull("Generating second entry", feed);
            assertEquals("Feed contains one entry", 1, feed.getEntries().size());
            entry = feed.getEntries().get(0);
            entriesToCleanup.add(FileStorage.getEntryFileForFeedEntry(feedId,
                    entry.getId().toString()));
            assertEquals("Entry retains title", "Second Post!",
                    entry.getTitle());
            assertEquals("Entry contains verb", "post",
                    entry.getSimpleExtension(new QName(
                            "http://activitystrea.ms/spec/1.0/", "verb",
                            "activity")));
            assertEquals("Feed retains body", "This is the body",
                    entry.getSummary());

            // verify predecessor signature
            signatureElement = entry.getFirstChild(new QName(
                    "http://www.w3.org/2000/09/xmldsig#", "Signature"));
            assertNotNull("Entry has signature", signatureElement);
            signatureElement = signatureElement.getFirstChild(new QName(
                    "http://www.w3.org/2000/09/xmldsig#", "SignatureValue"));
            assertNotNull("Entry has signature value", signatureElement);
            signatureElement = entry.getFirstChild(new QName(Common.NS_URI,
                    Common.PREDECESSOR));
            assertNotNull("Entry has predecessor", signatureElement);
            assertEquals("Entry predecessor id matches", predecessorId,
                    signatureElement.getAttributeValue("id"));
            assertEquals("Entry predecessor signature matches", signatureValue,
                    signatureElement.getText());

            // pull both entries
            feed = client.pull(feedId);
            assertEquals("Feed contains two entries", 2, feed.getEntries()
                    .size());
            entry = feed.getEntries().get(0);

            // file storage date granularity is currently too large for this
            // test to work
            // assertEquals("Feed lists most recent entry first",
            // "Second Post!", entry.getTitle() );

            // make sure we're retaining all entries
            for (int i = 0; i < 15; i++) {
                feed = client
                        .post(signingKeys, encryptionKeys.getPublic(),
                                "Multipost!", "post", null, null,
                                new String[] { idsToCleanup.iterator().next(),
                                        feedId }, new String[] { "fitter",
                                        "happier", "more productive" }, null,
                                null, null);
                entry = feed.getEntries().get(0);
                entriesToCleanup.add(FileStorage.getEntryFileForFeedEntry(
                        feedId, entry.getId().toString()));
            }
            feed = client.pull(feed.getId().toString());
            assertTrue("Feed has all entries", (17 == feed.getEntries().size()));

            // make sure server is paginating (in this case at 25 by default)
            for (int i = 0; i < 15; i++) {
                feed = client
                        .post(signingKeys, encryptionKeys.getPublic(),
                                "Multipost!", "post", null, null,
                                new String[] { idsToCleanup.iterator().next(),
                                        feedId }, new String[] { "fitter",
                                        "happier", "more productive" }, null,
                                null, null);
                entry = feed.getEntries().get(0);
                entriesToCleanup.add(FileStorage.getEntryFileForFeedEntry(
                        feedId, entry.getId().toString()));
            }
            feed = client.pull(feed.getId().toString());
            assertTrue("Feed has only first page of entries", (25 == feed
                    .getEntries().size()));
            
            // generate recipient keys
            KeyPair recipientKeys = Common.generateEncryptionKeyPair();

            // generate encrypted entry
            feed = client.post(signingKeys, encryptionKeys.getPublic(),
                    "Encrypted Post!", "post", null, "This is the body",
                    new String[] { idsToCleanup.iterator().next(), feedId },
                    new String[] { "fitter", "happier", "more productive" },
                    null, null, recipientKeys.getPublic(), null, null, null,
                    null, null, null);
            assertNotNull("Generating encrypted entry", feed);
            entry = feed.getEntries().get(0);
            entriesToCleanup.add(FileStorage.getEntryFileForFeedEntry(feedId,
                    entry.getId().toString()));
            assertFalse("Entry does not retain title",
                    "Encrypted Post!".equals(entry.getTitle()));
            assertFalse("Entry does not retain body",
                    "This is the body".equals(entry.getSummary()));

            // decrypt the entry
            signatureElement = entry.getFirstChild(new QName(
                    "http://www.w3.org/2001/04/xmlenc#", "EncryptedData"));
            assertNotNull("Entry is encrypted", signatureElement);
            signatureElement = signatureElement.getFirstChild(new QName(
                    "http://www.w3.org/2001/04/xmlenc#", "CipherData"));
            signatureElement = signatureElement.getFirstChild(new QName(
                    "http://www.w3.org/2001/04/xmlenc#", "CipherValue"));
            String encoded = signatureElement.getText();
            Entry decoded = (Entry) Client.decryptElement(
                    new Base64().decode(encoded), recipientKeys.getPrivate());
            assertTrue("Decoded entry retains title",
                    "Encrypted Post!".equals(decoded.getTitle()));
            assertTrue("Decoded entry retains body",
                    "This is the body".equals(decoded.getSummary()));
            
            // test pull of a single entry
            String existingId = entry.getId().toString();
            feed = client.pull(feedId, existingId);
            System.out.println(feedId + " : " + existingId );
            assertNotNull("Single entry feed result", feed);
            assertEquals("Single entry feed retains id", feedId, feed.getId().toString());
            assertEquals("Single entry feed contains one entry", 1, feed.getEntries().size());
            signatureElement = feed.getFirstChild(new QName(
                    "http://www.w3.org/2000/09/xmldsig#", "Signature"));
            assertNotNull("Single entry feed has signature", signatureElement);
            entry = feed.getEntries().get(0);
            assertEquals("Single entry retains id", existingId, entry.getId().toString());

            // test push to second server
            Server alternateServer = new Server();
            URL alternateUrl = alternateServer.getServiceURL();
            assertNotNull(client.push(feedId, alternateUrl));
            
            // clean up
            for (File file : entriesToCleanup) {
                assertTrue(file.getAbsolutePath(), file.exists());
                file.delete();
                assertFalse(file.getAbsolutePath(), file.exists());
            }
            File file;
            for (String id : idsToCleanup) {
                file = FileStorage.getFeedFileForFeedId(id);
                assertTrue(file.toString(), file.exists());
                file.delete();
                assertFalse(file.toString(), file.exists());
                file = file.getParentFile();
                assertTrue(file.toString(), file.exists());
                file.delete();
                assertFalse(file.toString(), file.exists());
            }

        } catch (Throwable t) {
            t.printStackTrace();
            fail();
        }
    }
}
