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
import java.util.UUID;

import javax.crypto.Cipher;
import javax.xml.namespace.QName;

import org.apache.abdera.Abdera;
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
import org.bouncycastle.crypto.engines.DESEngine;
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
        URL url = getURL(feedId, entryId);
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

    private Feed push(Feed feed, URL url) {
        try {
            AbderaClient client = new AbderaClient(Abdera.getInstance());
            ClientResponse response = client.post(new URL(url.toString() + '/' + feed.getId()
                    .toString()).toString(), feed);
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

    /**
     * Convenience to call post() without posting an entry. If the feed does not
     * exist on this server, a new feed is created.
     */
    public Feed post(KeyPair signingKeys, PublicKey encryptionKey)
            throws IOException, SecurityException, GeneralSecurityException {
        return this.post(signingKeys, encryptionKey, null, null, null, null,
                null, null, null, null, null);
    }

    /**
     * Convenience to call post() with a single status update. This is the
     * common case.
     */
    public Feed post(KeyPair signingKeys, PublicKey encryptionKey,
            String subject) throws IOException, SecurityException,
            GeneralSecurityException {
        return this.post(signingKeys, encryptionKey, subject, null, null, null,
                null, null, null, null, null);
    }

    /**
     * Convenience to call post() with a single entry but without updating the
     * feed attributes. This is the next most common case.
     */
    public Feed post(KeyPair signingKeys, PublicKey encryptionKey,
            String subject, String verb, Date publish, String body,
            String[] mentions, String[] tags, String[] mimetypes,
            byte[][] attachments, PublicKey recipientKey) throws IOException,
            SecurityException, GeneralSecurityException {
        return this.post(signingKeys, encryptionKey, subject, verb, publish,
                body, mentions, tags, mimetypes, attachments, recipientKey,
                null, null, null, null, null, null);
    }

    /**
     * Posts a new entry to the feed associated with the specified public
     * signing key to the home server. The signing keys and the encryption key
     * are required; all other options can be left null. At minimum, a status
     * must be specified, or no entries will be posted to the feed; in the case
     * of a new feed, an empty feed is generated.
     * 
     * @param signingKeys
     *            Required: the signing keys associated with public feed id of
     *            this feed.
     * @param encryptionKey
     *            Required: the public encryption key associated with this
     *            account; this public key will be used by others to encrypt
     *            private message for this account.
     * @param status
     *            A short text string no longer than 250 characters with no
     *            markup.
     * @param verb
     *            An activity streams verb; if unspecified, "post" is implicit.
     * @param publish
     *            The date on which this entry is publicly available, which may
     *            be in the future.
     * @param body
     *            An arbitrarily long text string that may be formatted in
     *            markdown; no HTML is allowed.
     * @param mentions
     *            Zero or more feed ids, or aliases to feed ids in the form of
     *            alias@homeserver
     * @param tags
     *            Zero or more tags (aka hashtags but without the hash); these
     *            are equivalent to atom categories.
     * @param mimetypes
     *            Zero or more mimetypes, each associated with the corresponding
     *            attachment in a parallel array.
     * @param attachments
     *            Zero or more binary attachments, each associated with the
     *            corresponding attachment in a parallel array.
     * @param recipientKey
     *            encrypts this entry using the specified public key so that
     *            only that key's owner can read it.
     * @param name
     *            Updates the author name associated with the feed.
     * @param email
     *            Updates the author email associated with the feed.
     * @param title
     *            Updates the title of the feed, or empty string to remove.
     * @param subtitle
     *            Updates the subtitle of the feed, or empty string to remove.
     * @param icon
     *            Updates the icon of the feed, or empty string to remove; this
     *            is the equivalent to a user profile pic.
     * @param logo
     *            Updates the subtitle of the feed, or empty string to remove;
     *            this is the equivalent to a user background image.
     * @param recipientKey
     *            encrypts this entry using the specified public key so that
     *            only that key's owner can read it.
     * @return The feed as posted to the home server.
     * @throws IOException
     * @throws SecurityException
     * @throws GeneralSecurityException
     */
    public Feed post(KeyPair signingKeys, PublicKey encryptionKey,
            String status, String verb, Date publish, String body,
            String[] mentions, String[] tags, String[] mimetypes,
            byte[][] attachments, PublicKey recipientKey, String name,
            String email, String title, String subtitle, String icon,
            String logo) throws IOException, SecurityException,
            GeneralSecurityException {
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
        }

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
        feed.declareNS(Common.NS_URI, Common.NS_ABBR);

        // ensure the correct keys are in place
        signatureElement = feed.getFirstChild(new QName(Common.NS_URI,
                Common.SIGN));
        if (signatureElement != null) {
            signatureElement.discard();
        }
        feed.addExtension(new QName(Common.NS_URI, Common.SIGN)).setText(
                Common.fromPublicKey(signingKeys.getPublic()));
        signatureElement = feed.getFirstChild(new QName(Common.NS_URI,
                Common.ENCRYPT));
        if (signatureElement != null) {
            signatureElement.discard();
        }
        feed.addExtension(new QName(Common.NS_URI, Common.ENCRYPT)).setText(
                Common.fromPublicKey(encryptionKey));
        feed.setId(feedId);
        feed.setMustPreserveWhitespace(false);

        // update feed properties
        if (name != null || email != null) {
            Person author = feed.getAuthor();
            if (author == null) {
                author = Abdera.getInstance().getFactory().newAuthor();
                feed.addAuthor(author);
            }
            if (name != null) {
                author.setName(name);
            }
            if (email != null) {
                author.setEmail(email);
            }
        }
        if (title != null) {
            feed.setTitle(title);
        }
        if (subtitle != null) {
            feed.setSubtitle(subtitle);
        }
        if (icon != null) {
            feed.setIcon(icon);
        }
        if (logo != null) {
            feed.setLogo(logo);
        }

        // subject is required to create an entry
        Entry entry = null;
        if (status != null) {

            // the arbitrary length limit
            // (mainly to benefit database implementors)
            if (status.length() > 250) {
                throw new IllegalArgumentException(
                        "Status cannot exceed 250 characters");
            }

            // create the new entry
            entry = Abdera.getInstance().newEntry();
            entry.setId("urn:uuid:"+UUID.randomUUID().toString());
            entry.setUpdated(new Date());
            if (publish != null) {
                entry.setPublished(publish);
            } else {
                entry.setPublished(entry.getUpdated());
            }
            entry.setTitle(status);
            if (verb != null) {
                feed.declareNS("http://activitystrea.ms/spec/1.0/", "activity");
                entry.addSimpleExtension(
                        new QName("http://activitystrea.ms/spec/1.0/", "verb",
                                "activity"), verb);
            }
            if (body != null) {
                entry.setSummary(body);
            }
            if (mentions != null) {
                for (String s : mentions) {
                    entry.addSimpleExtension(new QName(Common.NS_URI,
                            "mention", "trsst"), s);
                }
            }
            if (tags != null) {
                for (String s : tags) {
                    entry.addCategory(s);
                }
            }
            if (attachments != null) {
                // TODO: implement binary support
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
                                Common.PREDECESSOR_ID, mostRecentEntry.getId()
                                        .toString());
                    } else {
                        log.error("No signature value found for entry: "
                                + entry.getId());
                    }
                } else {
                    log.error("No signature found for entry: " + entry.getId());
                }
            }

            if (recipientKey != null) {
                try {
                    byte[] bytes = encryptElement(entry, recipientKey);
                    String encoded = new Base64(0, null, true)
                            .encodeToString(bytes);
                    StringWriter stringWriter = new StringWriter();
                    StreamWriter writer = Abdera.getInstance()
                            .getWriterFactory().newStreamWriter();
                    writer.setWriter(stringWriter);
                    writer.startEntry();
                    writer.writeId(entry.getId().toString());
                    writer.writeUpdated(entry.getUpdated());
                    writer.writePublished(entry.getPublished());
                    if (predecessor != null) {
                        writer.startElement(Common.PREDECESSOR, Common.NS_URI);
                        writer.writeElementText(predecessor);
                        writer.endElement();
                    }
                    writer.writeTitle("Encrypted post"); // arbitrary
                    // TODO: client should specify if mentions should
                    // be repeated outside the encryption envelope
                    // TODO: client could specify fake title/body/etc
                    // that would be overwritten during decryption.
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
                            + recipientKey, t);
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
        for ( Link link : feed.getLinks() ) {
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
        return push(feed, getURL(feedId, null));
    }

    private URL getURL(String feedId, String entryId) {
        URL url;
        try {
            url = new URL(serving, "/trsst/" + feedId);
            if (entryId != null) {
                url = new URL(url, "/trsst/" + feedId + "/" + entryId);
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

            IESCipher cipher = new IESCipher(new IESEngine(
                    new ECDHBasicAgreement(), new KDF2BytesGenerator(
                            new SHA1Digest()), new HMac(new SHA1Digest()),
                    new PaddedBufferedBlockCipher(new DESEngine())));
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
            IESCipher cipher = new IESCipher(new IESEngine(
                    new ECDHBasicAgreement(), new KDF2BytesGenerator(
                            new SHA1Digest()), new HMac(new SHA1Digest()),
                    new PaddedBufferedBlockCipher(new DESEngine())));
            cipher.engineInit(Cipher.DECRYPT_MODE, privateKey,
                    new SecureRandom());
            byte[] after = cipher.engineDoFinal(data, 0, data.length);

            ByteArrayInputStream input = new ByteArrayInputStream(after);
            result = Abdera.getInstance().getParser().parse(input).getRoot();
        } catch (Exception e) {
            log.error("Error while decrypting element", e);
            throw new SecurityException(e);
        }
        return result;
    }

    private final static org.slf4j.Logger log = org.slf4j.LoggerFactory
            .getLogger(Client.class);
}
