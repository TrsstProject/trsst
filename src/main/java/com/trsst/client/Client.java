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
package com.trsst.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Date;
import java.util.List;

import javax.crypto.Cipher;
import javax.xml.namespace.QName;

import org.apache.abdera.Abdera;
import org.apache.abdera.i18n.iri.IRI;
import org.apache.abdera.model.Content;
import org.apache.abdera.model.Document;
import org.apache.abdera.model.Element;
import org.apache.abdera.model.Entry;
import org.apache.abdera.model.Feed;
import org.apache.abdera.model.Link;
import org.apache.abdera.model.Person;
import org.apache.abdera.protocol.Response.ResponseType;
import org.apache.abdera.protocol.client.AbderaClient;
import org.apache.abdera.protocol.client.ClientResponse;
import org.apache.abdera.security.AbderaSecurity;
import org.apache.abdera.security.SecurityException;
import org.apache.abdera.security.Signature;
import org.apache.abdera.security.SignatureOptions;
import org.apache.abdera.writer.StreamWriter;
import org.apache.commons.codec.binary.Base64;
import org.bouncycastle.crypto.agreement.ECDHBasicAgreement;
import org.bouncycastle.crypto.digests.SHA1Digest;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.engines.IESEngine;
import org.bouncycastle.crypto.generators.KDF2BytesGenerator;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher;
import org.bouncycastle.jcajce.provider.asymmetric.ec.IESCipher;

import com.trsst.Common;

/**
 * Implements the protocol-level features of the Trsst platform: creating Feeds
 * and Entries, signing them, and optionally encrypting Entries when desired.
 * 
 * @author mpowers
 */
public class Client {

    private URL serving;

    /**
     * Client connecting to trsst services hosted at the specified url.
     */
    public Client(URL url) {
        this.serving = url;
    }

    /**
     * Returns a Feed for the specified feed id.
     * 
     * @param feedId
     * @return a Feed containing the latest entries for this feed id.
     */
    public Feed pull(String feedId) {
        return pull(feedId, null);
    }

    /**
     * Returns a Feed for the specified feed id that contains only the specified
     * entry.
     * 
     * @param feedId
     *            a feed id.
     * @param entryId
     *            an entry id.
     * @return a Feed containing only the specified entry.
     */
    public Feed pull(String feedId, String entryId) {
        AbderaClient client = new AbderaClient(Abdera.getInstance());
        URL url = getURL(serving, feedId, entryId);
        ClientResponse response = client.get(url.toString());
        if (response.getType() == ResponseType.SUCCESS) {
            Document<Feed> document = response.getDocument();
            if (document != null) {
                return document.getRoot();
            } else {
                log.warn("pull: no document for: " + url);
            }
        } else {
            log.debug("pull: no document found for: " + url + " : "
                    + response.getType());
        }
        return null;
    }

    /**
     * Pushes entries from the specified feed id on the home service to the
     * remote services hosted at the specified URL.
     * 
     * Push is used to notify a remote service of new entries that may be of
     * interest to users whose home is that service, or more broadly to publish
     * and propagate content across the network of participating trsst servers.
     * 
     * @param feedId
     *            a feed id.
     * @param url
     *            a URL to a remote trsst service
     * @return a Feed returned by the server successfully accepting the feed, or
     *         null if unsuccessful.
     */
    public Feed push(String feedId, URL url) {
        return push(feedId, null, url);
    }

    /**
     * Pushes a specified entry from the specified feed id on the home service
     * to the remote services hosted at the specified URL.
     * 
     * Pushing an entry is used to notify a service of a particular entry
     * directed to a user whose home is that service.
     * 
     * @param feedId
     *            a feed id.
     * @param entryId
     *            a feed id.
     * @param url
     *            a URL to a remote trsst service
     * @return a Feed returned by the server successfully accepting the feed, or
     *         null if unsuccessful.
     */
    public Feed push(String feedId, String entryId, URL url) {
        return push(pull(feedId, entryId), url);
    }

    private Feed push(Feed feed, String contentId, String contentType,
            byte[] content, URL url) {
        try {
            AbderaClient client = new AbderaClient(Abdera.getInstance());
            url = new URL(url.toString() + '/'
                    + Common.fromFeedUrn(feed.getId()));
            ClientResponse response;
            if (contentId != null) {
                response = client.post(url.toString(),
                        new MultiPartRequestEntity(feed,
                                new byte[][] { content },
                                new String[] { contentId },
                                new String[] { contentType }));
            } else {
                response = client.post(url.toString(), feed);
            }
            if (response.getType() == ResponseType.SUCCESS) {
                Document<Feed> document = response.getDocument();
                if (document != null) {
                    return document.getRoot();
                } else {
                    log.warn("push: no document for: " + url);
                }
            } else {
                System.err.println("Sent:");
                System.err.println(feed);
                log.error("push: invalid response for: " + url + " : "
                        + response.getType());
                System.err.println("Received:");
                System.err.println(response.getDocument().getRoot());
            }
        } catch (MalformedURLException e) {
            log.error("push: bad url: " + url, e);
        }
        return null;
    }

    private Feed push(Feed feed, URL url) {
        return push(feed, null, null, null, url);
    }

    /**
     * Posts a new entry to the feed associated with the specified public
     * signing key to the home server, creating a new feed if needed.
     * 
     * @param signingKeys
     *            Required: the signing keys associated with public feed id of
     *            this feed.
     * @param encryptionKey
     *            Required: the public encryption key associated with this
     *            account; this public key will be used by others to encrypt
     *            private message for this account.
     * @param options
     *            The data to be posted.
     * @return The feed as posted to the home server.
     * @throws IOException
     * @throws SecurityException
     * @throws GeneralSecurityException
     */
    public Feed post(KeyPair signingKeys, PublicKey encryptionKey,
            EntryOptions options, FeedOptions feedOptions) throws IOException,
            SecurityException, GeneralSecurityException {
        // inlining all the steps to help implementors and porters (and
        // debuggers)

        // configure for signing
        Element signedNode, signatureElement, keyInfo;
        AbderaSecurity security = new AbderaSecurity(Abdera.getInstance());
        Signature signer = security.getSignature();

        String feedId = Common.toFeedId(signingKeys.getPublic());
        Feed feed = pull(feedId);
        if (feed == null) {
            feed = Abdera.getInstance().newFeed();
            feed.declareNS(Common.NS_URI, Common.NS_ABBR);
            feed.setBaseUri(new IRI(serving + "/" + feedId + "/"));
        }
        String contentId = null;

        // remove each entry and retain the most recent one (if any)
        List<Entry> entries = feed.getEntries();
        Entry mostRecentEntry = null;
        for (Entry entry : entries) {
            if (mostRecentEntry == null || mostRecentEntry.getUpdated() == null
                    || mostRecentEntry.getUpdated().before(entry.getUpdated())) {
                mostRecentEntry = entry;
            }
            entry.discard();
        }

        // update and sign feed (without any entries)
        feed.setUpdated(new Date());

        // ensure the correct keys are in place
        signatureElement = feed.getFirstChild(new QName(Common.NS_URI,
                Common.SIGN));
        if (signatureElement != null) {
            signatureElement.discard();
        }
        feed.addExtension(new QName(Common.NS_URI, Common.SIGN)).setText(
                Common.toX509FromPublicKey(signingKeys.getPublic()));
        signatureElement = feed.getFirstChild(new QName(Common.NS_URI,
                Common.ENCRYPT));
        if (signatureElement != null) {
            signatureElement.discard();
        }
        feed.addExtension(new QName(Common.NS_URI, Common.ENCRYPT)).setText(
                Common.toX509FromPublicKey(encryptionKey));
        feed.setId(Common.toFeedUrn(feedId));
        feed.setMustPreserveWhitespace(false);

        // update feed properties
        if (feedOptions.name != null || feedOptions.email != null) {
            Person author = feed.getAuthor();
            if (author == null) {
                author = Abdera.getInstance().getFactory().newAuthor();
                feed.addAuthor(author);
            }
            if (feedOptions.name != null) {
                author.setName(feedOptions.name);
            }
            if (feedOptions.email != null) {
                author.setEmail(feedOptions.email);
            }
        }
        if (feedOptions.title != null) {
            feed.setTitle(feedOptions.title);
        }
        if (feedOptions.subtitle != null) {
            feed.setSubtitle(feedOptions.subtitle);
        }
        if (feedOptions.icon != null) {
            feed.setIcon(feedOptions.icon);
        }
        if (feedOptions.logo != null) {
            feed.setLogo(feedOptions.logo);
        }

        // subject is required to create an entry
        Entry entry = null;
        if (options.status != null) {

            // the arbitrary length limit
            // (mainly to benefit database implementors)
            if (options.status.length() > 250) {
                throw new IllegalArgumentException(
                        "Status cannot exceed 250 characters");
            }

            // create the new entry
            entry = Abdera.getInstance().newEntry();
            entry.setUpdated(feed.getUpdated());
            entry.setId(Common.toEntryUrn(Common.toEntryId(feed.getUpdated())));
            if (options.publish != null) {
                entry.setPublished(options.publish);
            } else {
                entry.setPublished(entry.getUpdated());
            }
            entry.setTitle(options.status);
            if (options.verb != null) {
                feed.declareNS("http://activitystrea.ms/spec/1.0/", "activity");
                entry.addSimpleExtension(
                        new QName("http://activitystrea.ms/spec/1.0/", "verb",
                                "activity"), options.verb);
            }
            if (options.body != null) {
                entry.setSummary(options.body);
            }
            if (options.mentions != null) {
                for (String s : options.mentions) {
                    entry.addSimpleExtension(new QName(Common.NS_URI,
                            "mention", "trsst"), s);
                }
            }
            if (options.tags != null) {
                for (String s : options.tags) {
                    entry.addCategory(s);
                }
            }

            if (options.content != null) {

                // encrypt before hashing if necessary
                if (options.recipientKey != null) {
                    options.content = encryptBytes(options.content,
                            options.recipientKey);
                }

                // calculate digest
                byte[] digest = Common.ripemd160(options.content); // Common.keyhash(content);
                contentId = new Base64(0, null, true).encodeToString(digest);
                entry.setContent(new IRI(contentId), options.mimetype);

                // use a base uri so src attribute is simpler to process
                entry.getContentElement().setBaseUri(
                        Common.fromEntryUrn(entry.getId()) + "/");
                entry.getContentElement().setAttributeValue(
                        new QName(Common.NS_URI, "hash", "trsst"), "ripemd160");
            } else if (options.url != null) {
                Content content = Abdera.getInstance().getFactory().newContent();
                content.setSrc(options.url);
                entry.setContentElement(content);
                //content.setAttributeValue("src", options.url);
            }

            // add the previous entry's signature value
            String predecessor = null;
            if (mostRecentEntry != null) {
                signatureElement = mostRecentEntry.getFirstChild(new QName(
                        "http://www.w3.org/2000/09/xmldsig#", "Signature"));
                if (signatureElement != null) {
                    signatureElement = signatureElement
                            .getFirstChild(new QName(
                                    "http://www.w3.org/2000/09/xmldsig#",
                                    "SignatureValue"));
                    if (signatureElement != null) {
                        predecessor = signatureElement.getText();
                        signatureElement = entry.addExtension(new QName(
                                Common.NS_URI, Common.PREDECESSOR));
                        signatureElement.setText(predecessor);
                        signatureElement.setAttributeValue(
                                Common.PREDECESSOR_ID,
                                Common.fromEntryUrn(mostRecentEntry.getId()));
                    } else {
                        log.error("No signature value found for entry: "
                                + Common.fromEntryUrn(entry.getId()));
                    }
                } else {
                    log.error("No signature found for entry: "
                            + Common.fromEntryUrn(entry.getId()));
                }
            }

            if (options.recipientKey != null) {
                try {
                    byte[] bytes = encryptElement(entry, options.recipientKey);
                    String encoded = new Base64(0, null, true)
                            .encodeToString(bytes);
                    StringWriter stringWriter = new StringWriter();
                    StreamWriter writer = Abdera.getInstance()
                            .getWriterFactory().newStreamWriter();
                    writer.setWriter(stringWriter);
                    writer.startEntry();
                    writer.writeId(Common.fromEntryUrn(entry.getId()));
                    writer.writeUpdated(entry.getUpdated());
                    writer.writePublished(entry.getPublished());
                    if (predecessor != null) {
                        writer.startElement(Common.PREDECESSOR, Common.NS_URI);
                        writer.writeElementText(predecessor);
                        writer.endElement();
                    }
                    if (options.publicOptions != null) {
                        // these are options that will be publicly visible
                        if (options.publicOptions.status != null) {
                            writer.writeTitle(options.publicOptions.status);
                        } else {
                            writer.writeTitle("Encrypted message"); // arbitrary
                        }
                        if (options.publicOptions.body != null) {
                            writer.writeSummary(options.publicOptions.body);
                        }
                        if (options.publicOptions.verb != null) {
                            writer.startElement("verb",
                                    "http://activitystrea.ms/spec/1.0/");
                            writer.writeElementText(options.publicOptions.verb);
                            writer.endElement();
                        }
                        // TODO: write mentions
                        // TODO: write tags
                    } else {
                        writer.writeTitle("Encrypted message"); // arbitrary
                    }
                    writer.startElement("EncryptedData",
                            "http://www.w3.org/2001/04/xmlenc#");
                    writer.startElement("CipherData",
                            "http://www.w3.org/2001/04/xmlenc#");
                    writer.startElement("CipherValue",
                            "http://www.w3.org/2001/04/xmlenc#");
                    writer.writeElementText(encoded);
                    writer.endElement();
                    writer.endElement();
                    writer.endElement();
                    writer.endEntry();
                    writer.flush();
                    // this constructed entry now replaces the encrypted entry
                    entry = (Entry) Abdera.getInstance().getParserFactory()
                            .getParser()
                            .parse(new StringReader(stringWriter.toString()))
                            .getRoot();
                    // System.out.println(stringWriter.toString());
                } catch (Throwable t) {
                    log.error("Unexpected error while encrypting, exiting: "
                            + options.recipientKey, t);
                    t.printStackTrace();
                    throw new IllegalArgumentException("Unexpected error: " + t);
                }
            }

            // sign the new entry
            signedNode = signer.sign(entry,
                    getSignatureOptions(signer, signingKeys));
            signatureElement = signedNode.getFirstChild(new QName(
                    "http://www.w3.org/2000/09/xmldsig#", "Signature"));
            keyInfo = signatureElement.getFirstChild(new QName(
                    "http://www.w3.org/2000/09/xmldsig#", "KeyInfo"));
            if (keyInfo != null) {
                // remove key info (because we're not using certs)
                keyInfo.discard();
            }
            entry.addExtension(signatureElement);
        }

        // remove existing feed signature element if any
        signatureElement = feed.getFirstChild(new QName(
                "http://www.w3.org/2000/09/xmldsig#", "Signature"));
        if (signatureElement != null) {
            signatureElement.discard();
        }

        // remove all links before signing
        for (Link link : feed.getLinks()) {
            link.discard();
        }

        // sign the feed
        signedNode = signer
                .sign(feed, getSignatureOptions(signer, signingKeys));
        signatureElement = signedNode.getFirstChild(new QName(
                "http://www.w3.org/2000/09/xmldsig#", "Signature"));
        keyInfo = signatureElement.getFirstChild(new QName(
                "http://www.w3.org/2000/09/xmldsig#", "KeyInfo"));
        if (keyInfo != null) {
            // remove key info (because we're not using certs)
            keyInfo.discard();
        }
        feed.addExtension(signatureElement);

        // add the new entry to the feed, if there is one,
        // only after we have signed the feed
        if (entry != null) {
            feed.addEntry(entry);
        }

        // post to server
        if (contentId != null) {
            return push(feed, contentId, options.mimetype, options.content,
                    serving);
        }
        return push(feed, serving);
    }

    private static final URL getURL(URL base, String feedId, String entryId) {
        URL url;
        try {
            String s = base.toString();
            if (s.endsWith("/")) {
                s = s.substring(0, s.length() - 1);
            }
            url = new URL(s + "/" + feedId);
            if (entryId != null) {
                url = new URL(url.toString() + "/" + entryId);
            }
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid input: " + feedId
                    + " : " + entryId);
        }
        return url;
    }

    private final static SignatureOptions getSignatureOptions(Signature signer,
            KeyPair signingKeys) throws SecurityException {
        SignatureOptions options = signer.getDefaultSignatureOptions();
        options.setSigningAlgorithm("http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha1");
        options.setSignLinks(false); // don't sign atom:links
        options.setPublicKey(signingKeys.getPublic());
        options.setSigningKey(signingKeys.getPrivate());
        return options;
    }

    public static byte[] encryptElement(Element element, PublicKey publicKey)
            throws SecurityException {
        byte[] after = null;
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            element.writeTo(output);
            byte[] before = output.toByteArray();
            after = encryptBytes(before, publicKey);
        } catch (Exception e) {
            log.error("Error while encrypting element", e);
            throw new SecurityException(e);
        }
        return after;
    }

    private static byte[] encryptBytes(byte[] before, PublicKey publicKey)
            throws SecurityException {
        byte[] after = null;
        try {
            IESCipher cipher = new IESCipher(new IESEngine(
                    new ECDHBasicAgreement(), new KDF2BytesGenerator(
                            new SHA1Digest()), new HMac(new SHA256Digest()),
                    new PaddedBufferedBlockCipher(new AESEngine())));
            cipher.engineInit(Cipher.ENCRYPT_MODE, publicKey,
                    new SecureRandom());
            after = cipher.engineDoFinal(before, 0, before.length);
        } catch (Exception e) {
            log.error("Error while encrypting element", e);
            throw new SecurityException(e);
        }
        return after;
    }

    public static Element decryptElement(byte[] data, PrivateKey privateKey)
            throws SecurityException {
        Element result;

        try {
            byte[] after = decryptBytes(data, privateKey);
            ByteArrayInputStream input = new ByteArrayInputStream(after);
            result = Abdera.getInstance().getParser().parse(input).getRoot();
        } catch (Exception e) {
            log.error("Error while decrypting: ", e);
            throw new SecurityException(e);
        }
        return result;
    }

    private static byte[] decryptBytes(byte[] data, PrivateKey privateKey)
            throws SecurityException {
        try {
            IESCipher cipher = new IESCipher(new IESEngine(
                    new ECDHBasicAgreement(), new KDF2BytesGenerator(
                            new SHA1Digest()), new HMac(new SHA256Digest()),
                    new PaddedBufferedBlockCipher(new AESEngine())));
            cipher.engineInit(Cipher.DECRYPT_MODE, privateKey,
                    new SecureRandom());
            return cipher.engineDoFinal(data, 0, data.length);
        } catch (Exception e) {
            log.error("Error while decrypting: ");
            throw new SecurityException(e);
        }
    }

    private final static org.slf4j.Logger log = org.slf4j.LoggerFactory
            .getLogger(Client.class);
}
