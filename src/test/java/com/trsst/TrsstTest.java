package com.trsst;

import java.io.File;
import java.io.StringReader;
import java.net.URL;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;

import javax.xml.namespace.QName;

import org.apache.abdera.Abdera;
import org.apache.abdera.model.Element;
import org.apache.abdera.model.Entry;
import org.apache.abdera.model.Feed;
import org.bouncycastle.util.Arrays;

import com.google.common.io.Files;
import com.trsst.client.Client;
import com.trsst.client.EntryOptions;
import com.trsst.client.FeedOptions;
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
            // System.setProperty("org.slf4j.simpleLogger.defaultLogLevel",
            // "debug");

            Feed feed;
            Entry entry;

            // write test files to temp directory
            File tmp = Files.createTempDir();
            tmp.deleteOnExit();

            URL serviceURL = null;
            if (System.getProperty("com.trsst.TrsstTest.server") == null) {
                // start a local server for testing
                System.setProperty("com.trsst.server.storage",
                        tmp.getAbsolutePath());
                Server server = new Server();
                serviceURL = server.getServiceURL();
            } else {
                serviceURL = new URL(
                        System.getProperty("com.trsst.TrsstTest.server"));
            }
            // serviceURL = new URL("http://localhost:8888/trsst");
            assertNotNull(serviceURL);

            String feedId;
            Client client = new Client(serviceURL);
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

            // public key serialization
            publicKey = signingKeys.getPublic();
            assertEquals("Signing keys serialize",
                    Common.toX509FromPublicKey(publicKey),
                    Common.toX509FromPublicKey(Common
                            .toPublicKeyFromX509(Common
                                    .toX509FromPublicKey(publicKey))));
            publicKey = encryptionKeys.getPublic();
            assertEquals("Encryption keys serialize",
                    Common.toX509FromPublicKey(publicKey),
                    Common.toX509FromPublicKey(Common
                            .toPublicKeyFromX509(Common
                                    .toX509FromPublicKey(publicKey))));

            // generate feed with no entries
            feed = client.post(signingKeys, encryptionKeys, new EntryOptions(),
                    new FeedOptions());
            assertNotNull("Generating empty feed", feed);
            assertEquals("Empty feed retains id",
                    Common.fromFeedUrn(feed.getId()), feedId);
            assertEquals("Empty feed contains no entries", feed.getEntries()
                    .size(), 0);
            signatureElement = feed.getFirstChild(new QName(
                    "http://www.w3.org/2000/09/xmldsig#", "Signature"));
            assertNotNull("Feed has signature", signatureElement);

            // verify string serialization roundtrip
            String raw = feed.toString();
            feed = (Feed) Abdera.getInstance().getParser()
                    .parse(new StringReader(raw)).getRoot();
            feed = client.push(feed, serviceURL);
            assertNotNull("String serialization", feed);
            assertEquals("Serialized feed retains id",
                    Common.fromFeedUrn(feed.getId()), feedId);
            assertEquals("Serialized feed contains no entries", feed
                    .getEntries().size(), 0);
            signatureElement = feed.getFirstChild(new QName(
                    "http://www.w3.org/2000/09/xmldsig#", "Signature"));
            assertNotNull("Serialized feed has signature", signatureElement);

            // generate account
            signingKeys = Common.generateSigningKeyPair();
            assertNotNull("Generating signing keys", signingKeys);
            encryptionKeys = Common.generateEncryptionKeyPair();
            assertNotNull("Generating encryption keys", encryptionKeys);
            feedId = Common.toFeedId(signingKeys.getPublic());

            // generate feed with entry
            feed = client.post(signingKeys, encryptionKeys,
                    new EntryOptions().setStatus("First Post!"),
                    new FeedOptions());
            assertNotNull("Generating feed with entry", feed);
            assertEquals("Feed retains id", feedId,
                    Common.fromFeedUrn(feed.getId()));
            assertEquals("Feed contains one entry", 1, feed.getEntries().size());
            signatureElement = feed.getFirstChild(new QName(
                    "http://www.w3.org/2000/09/xmldsig#", "Signature"));
            assertNotNull("Feed has signature", signatureElement);
            entry = feed.getEntries().get(0);
            assertEquals("Entry retains title", "First Post!", entry.getTitle());

            // verify string serialization with-entry roundtrip
            raw = feed.toString();
            feed = (Feed) Abdera.getInstance().getParser()
                    .parse(new StringReader(raw)).getRoot();
            feed = client.push(feed, serviceURL);
            assertNotNull("String serialization with entry", feed);
            assertEquals("Serialized feed with entry retains id",
                    Common.fromFeedUrn(feed.getId()), feedId);
            assertEquals("Serialized feed contains one entry", 1, feed
                    .getEntries().size());
            signatureElement = feed.getFirstChild(new QName(
                    "http://www.w3.org/2000/09/xmldsig#", "Signature"));
            assertNotNull("Serialized feed with entry has signature",
                    signatureElement);

            // verify edited string fails to validate
            raw = feed.toString();
            raw = raw.replace("First", "Second");
            feed = (Feed) Abdera.getInstance().getParser()
                    .parse(new StringReader(raw)).getRoot();
            // this generates some errors on the console but it's ok
            try {
                feed = null;
                feed = client.push(feed, serviceURL);
            } catch (Throwable t) {
                // ignore: this is the exception we want to test
            }
            assertNull("Verification fails with edited entry", feed);

            // encryption roundtrip
            byte[] key = Crypto.generateAESKey();
            long timestamp = System.currentTimeMillis();
            KeyPair a = Common.generateEncryptionKeyPair();
            KeyPair b = Common.generateEncryptionKeyPair();
            byte[] bytes = Crypto.encryptKeyWithECDH(key, timestamp,
                    a.getPublic(), b.getPrivate());
            byte[] decrypted = Crypto.decryptKeyWithECDH(bytes, timestamp,
                    b.getPublic(), a.getPrivate());
            assertTrue("ECDH Encryption round trip test",
                    Arrays.areEqual(key, decrypted));

            // serialized entry encryption roundtrip
            String test = entry.toString();
            bytes = Client.encryptElementAES(entry, key);
            Element element = Client.decryptElementAES(bytes, key);
            assertEquals("AES Encryption round trip test", test,
                    element.toString());

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
            feed = client.post(
                    signingKeys,
                    encryptionKeys,
                    new EntryOptions()
                            .setStatus("Second Post!")
                            .setVerb("post")
                            .setBody("This is the body")
                            .setMentions(new String[] { feedId, feedId })
                            .setTags(
                                    new String[] { "fitter", "happier",
                                            "more productive" }),
                    new FeedOptions());
            assertNotNull("Generating second entry", feed);
            assertEquals("Feed contains one entry", 1, feed.getEntries().size());
            entry = feed.getEntries().get(0);
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

            // mark both entries as deleted
            String firstIdToDelete = feed.getEntries().get(0).getId()
                    .toString();
            String secondIdToDelete = feed.getEntries().get(1).getId()
                    .toString();
            feed = client
                    .post(signingKeys,
                            encryptionKeys,
                            new EntryOptions().setVerb("delete").setMentions(
                                    new String[] { firstIdToDelete,
                                            secondIdToDelete }),
                            new FeedOptions());
            assertNotNull("Delete operation succeeded", feed);
            entry = feed.getEntries().get(0);

            feed = client.pull(firstIdToDelete);
            entry = feed.getEntries().get(0);
            assertEquals("First entry was deleted", "deleted",
                    entry.getSimpleExtension(new QName(
                            "http://activitystrea.ms/spec/1.0/", "verb")));
            feed = client.pull(secondIdToDelete);
            entry = feed.getEntries().get(0);
            assertEquals("First entry was deleted", "deleted",
                    entry.getSimpleExtension(new QName(
                            "http://activitystrea.ms/spec/1.0/", "verb")));

            // file storage date granularity is currently too large for this
            // test to work
            // assertEquals("Feed lists most recent entry first",
            // "Second Post!", entry.getTitle() );

            // make sure we're retaining all entries
            for (int i = 0; i < 15; i++) {
                feed = client.post(
                        signingKeys,
                        encryptionKeys,
                        new EntryOptions()
                                .setStatus("Multipost!")
                                .setVerb("post")
                                .setBody("This is the body")
                                .setMentions(new String[] { feedId })
                                .setTags(
                                        new String[] { "fitter", "happier",
                                                "more productive" }),
                        new FeedOptions());
                entry = feed.getEntries().get(0);
            }
            feed = client.pull(Common.fromFeedUrn(feed.getId()));
            assertTrue("Feed has all entries", (18 == feed.getEntries().size()));

            // make sure server is paginating (in this case at 25 by default)
            for (int i = 0; i < 15; i++) {
                feed = client.post(
                        signingKeys,
                        encryptionKeys,
                        new EntryOptions()
                                .setStatus("Multipost!")
                                .setVerb("post")
                                .setBody("This is the body")
                                .setMentions(new String[] { feedId })
                                .setTags(
                                        new String[] { "fitter", "happier",
                                                "more productive" }),
                        new FeedOptions());
                entry = feed.getEntries().get(0);
            }
            feed = client.pull(Common.fromFeedUrn(feed.getId()) + "?count=25");
            assertTrue("Feed has only first page of entries", (25 == feed
                    .getEntries().size()));

            // test pull of a single entry
            long existingId = Common.toEntryId(entry.getId());
            feed = client.pull(entry.getId().toString());
            assertNotNull("Single entry feed result", feed);
            assertEquals("Single entry feed retains id", feedId,
                    Common.fromFeedUrn(feed.getId()));
            assertEquals("Single entry feed contains one entry", 1, feed
                    .getEntries().size());
            signatureElement = feed.getFirstChild(new QName(
                    "http://www.w3.org/2000/09/xmldsig#", "Signature"));
            assertNotNull("Single entry feed has signature", signatureElement);
            entry = feed.getEntries().get(0);
            assertEquals("Single entry retains id", existingId,
                    Common.toEntryId(entry.getId()));

            // generate recipient keys
            KeyPair recipientKeys = Common.generateEncryptionKeyPair();

            // generate encrypted entry
            feed = client
                    .post(signingKeys,
                            encryptionKeys,
                            new EntryOptions()
                                    .setStatus("This is the encrypted entry")
                                    .setBody("This is the encrypted body")
                                    .setContentUrl("http://www.trsst.com")
                                    .encryptWith(
                                            new PublicKey[] {
                                                    recipientKeys.getPublic(),
                                                    encryptionKeys.getPublic() },
                                            new EntryOptions()
                                                    .setMentions(
                                                            new String[] {
                                                                    "182pdh1P6be28uHCUpfZnrQ5M7AJcuXLX2",
                                                                    "1JMcxLznMbyDYerWo3WUpTPhbwjFq97MeZ" })
                                                    .setTags(
                                                            new String[] {
                                                                    "public",
                                                                    "unencrypted",
                                                                    "in the clear" })
                                                    .setStatus(
                                                            "Unencrypted title with encrypted entry")),
                            new FeedOptions());
            entry = feed.getEntries().get(0);
            // pull and decrypt the entry
            feed = client.pull(entry.getId().toString(),
                    new PrivateKey[] { encryptionKeys.getPrivate() });
            assertNotNull("Generated encrypted entry", feed);
            entry = feed.getEntries().get(0);
            assertFalse("Entry does not retain status",
                    "This is the encrypted entry".equals(entry.getTitle()));
            assertFalse("Entry does not retain body",
                    "This is the encrypted body".equals(entry.getSummary()));
            assertEquals("Entry retains tags", 3, entry.getCategories().size());
            assertEquals(
                    "Entry retains mentions",
                    2,
                    entry.getExtensions(
                            new QName(Common.NS_URI, "mention", "trsst"))
                            .size());

            Element contentElement = entry.getContentElement();
            assertTrue("Decoded element is an Entry",
                    contentElement.getFirstChild() instanceof Entry);
            Entry decoded = (Entry) contentElement.getFirstChild();
            assertTrue("Decoded entry retains status",
                    "This is the encrypted entry".equals(decoded.getTitle()));
            assertTrue("Decoded entry retains body",
                    "This is the encrypted body".equals(decoded.getSummary()));
            assertTrue("Decoded entry retains content",
                    "http://www.trsst.com".equals(decoded.getContentSrc()
                            .toString()));

            // write and read the keypair
            Command.writeEncryptionKeyPair(recipientKeys, "tmp", new File(tmp,
                    "keytest.p12"), new char[] { 'p' });
            recipientKeys = Command.readEncryptionKeyPair("tmp", new File(tmp,
                    "keytest.p12"), new char[] { 'p' });
            assertNotNull("Write and read keys from keystore", recipientKeys);

            // test push to second server
            // TODO: need to implement server sync test here
            // Server alternateServer = new Server();
            // URL alternateUrl = alternateServer.getServiceURL();
            // assertNotNull(client.push(feedId, alternateUrl));

        } catch (Throwable t) {
            t.printStackTrace();
            fail();
        }
    }
}
