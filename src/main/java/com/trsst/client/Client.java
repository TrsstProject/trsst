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
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import javax.activation.MimeType;
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
import org.apache.commons.httpclient.protocol.Protocol;
import org.apache.commons.httpclient.protocol.ProtocolSocketFactory;

import com.trsst.Common;
import com.trsst.Crypto;

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

        // FLAG: allow anonymous SSL:
        // trsst clients aren't vulnerable to MITM
        // because we don't trust the man anyway;
        // the worst he can do is drop our messages,
        // but we keep our copy and others will see
        // the gap in the blog chain.
        Protocol anonhttps = new Protocol("https",
                (ProtocolSocketFactory) new AnonymSSLSocketFactory(), 443);
        Protocol.registerProtocol("https", anonhttps);
    }

    /**
     * Returns the url this client is using to connect to services.
     */
    public URL getURL() {
        return this.serving;
    }

    /**
     * Returns a Feed for the specified feed id, and will attempt to decrypt any
     * encrypted content with the specified key.
     * 
     * @param urn
     *            a feed or entry urn id.
     * @param decryptionKey
     *            one or more private keys used to attempt to decrypt content.
     * @return a Feed containing the latest entries for this feed id.
     */
    public Feed pull(String urn, PrivateKey[] decryptionKeys) {
        Feed feed = pull(urn);
        Content content;
        MimeType contentType;
        for (Entry entry : feed.getEntries()) {
            content = entry.getContentElement();
            if (content != null
                    && (contentType = content.getMimeType()) != null
                    && "application/xenc+xml".equals(contentType.toString())) {

                // if this message was intended for us, we will be able to
                // decrypt one of the elements into an AES key to decrypt the
                // encrypted entry itself

                QName publicEncryptName = new QName(Common.NS_URI,
                        Common.ENCRYPT);
                QName publicSignName = new QName(Common.NS_URI, Common.SIGN);
                QName encryptedDataName = new QName(
                        "http://www.w3.org/2001/04/xmlenc#", "EncryptedData");
                QName cipherDataName = new QName(
                        "http://www.w3.org/2001/04/xmlenc#", "CipherData");
                QName cipherValueName = new QName(
                        "http://www.w3.org/2001/04/xmlenc#", "CipherValue");

                String encodedBytes;
                byte[] decodedBytes;
                Element publicKeyElement, cipherData, cipherValue, result;
                List<Element> encryptedElements = content.getElements();
                int lastIndex = encryptedElements.size() - 1;
                Element element;
                PublicKey publicKey = null;
                byte[] decryptedKey = null;

                publicKeyElement = feed.getFirstChild(publicEncryptName);
                if (publicKeyElement == null) {
                    // fall back on signing key
                    publicKeyElement = feed.getFirstChild(publicSignName);
                }
                if (publicKeyElement != null
                        && publicKeyElement.getText() != null) {
                    try {
                        publicKey = Common.toPublicKeyFromX509(publicKeyElement
                                .getText());
                    } catch (GeneralSecurityException gse) {
                        log.error("Could not parse public key: "
                                + publicKeyElement);
                    }
                }

                if (publicKey != null) {

                    // TODO: if we're the author, we can start loop at
                    // (lastIndex-1)
                    for (int i = 0; i < encryptedElements.size(); i++) {
                        element = encryptedElements.get(i);
                        if (encryptedDataName.equals(element.getQName())) {
                            cipherData = element.getFirstChild(cipherDataName);
                            if (cipherData != null) {
                                cipherValue = cipherData
                                        .getFirstChild(cipherValueName);
                                if (cipherValue != null) {
                                    encodedBytes = cipherValue.getText();
                                    if (encodedBytes != null) {
                                        decodedBytes = new Base64()
                                                .decode(encodedBytes);
                                        if (i != lastIndex) {
                                            // if we're not at the last index
                                            // (the payload) so we should
                                            // attempt
                                            // to decrypt this AES key
                                            for (PrivateKey decryptionKey : decryptionKeys) {
                                                try {
                                                    decryptedKey = Crypto
                                                            .decryptKeyWithECDH(
                                                                    decodedBytes,
                                                                    entry.getUpdated()
                                                                            .getTime(),
                                                                    publicKey,
                                                                    decryptionKey);
                                                    if (decryptedKey != null) {
                                                        // success:
                                                        // skip to lastIndex
                                                        i = lastIndex - 1;
                                                        break;
                                                    }
                                                } catch (GeneralSecurityException e) {
                                                    // key did not fit
                                                    log.trace(
                                                            "Could not decrypt key: "
                                                                    + entry.getId(),
                                                            e);
                                                } catch (Throwable t) {
                                                    log.warn(
                                                            "Error while decrypting key on entry: "
                                                                    + entry.getId(),
                                                            t);
                                                }
                                            }
                                        } else if (decryptedKey != null) {
                                            // if we're at the last index
                                            // (the payload) and we have an
                                            // AES key: attempt to decrypt
                                            try {
                                                result = decryptElementAES(
                                                        decodedBytes,
                                                        decryptedKey);
                                                for (Element ee : encryptedElements) {
                                                    ee.discard();
                                                }
                                                content.setValueElement(result);
                                                break;
                                            } catch (SecurityException e) {
                                                log.error(
                                                        "Key did not decrypt element: "
                                                                + entry.getId(),
                                                        e);
                                            } catch (Throwable t) {
                                                log.warn(
                                                        "Could not decrypt element on entry: "
                                                                + entry.getId(),
                                                        t);
                                            }
                                        }
                                    } else {
                                        log.warn("No cipher text for entry: "
                                                + entry.getId());
                                    }
                                } else {
                                    log.warn("No cipher value for entry: "
                                            + entry.getId());
                                }
                            } else {
                                log.warn("No cipher data for entry: "
                                        + entry.getId());
                            }
                        }
                    }

                } else {
                    log.error("No public key for feed: " + feed.getId());
                }
            }
        }
        return feed;
    }

    /**
     * Returns a Feed for the specified urn. Filters may be applied as url
     * parameters on the urn, e.g. "?tag=birthday&tag=happy"
     * 
     * @param urn
     *            a feed or entry urn id.
     * @return a Feed containing the latest entries for this feed id.
     */
    public Feed pull(String urn) {
        AbderaClient client = new AbderaClient(Abdera.getInstance(),
                Common.getBuildString());

        if (urn.startsWith("urn:feed:")) {
            urn = urn.substring("urn:feed:".length());
        } else if (urn.startsWith("urn:entry:")) {
            urn = urn.substring("urn:entry:".length());
            // convert from urn to a trsst url path
            int sep = urn.lastIndexOf(':');
            if (sep != -1) {
                urn = urn.substring(0, sep) + '/' + urn.substring(sep + 1);
            }
        }

        URL url = null;
        try {
            url = new URL(serving + "/" + urn);
        } catch (MalformedURLException e) {
            System.err.println("Invalid urn: " + serving + "/" + urn);
            return null;
        }

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
        return push(pull(feedId), url);
    }

    private Feed push(Feed feed, String[] contentId, String[] contentType,
            byte[][] content, URL url) {
        try {
            AbderaClient client = new AbderaClient(Abdera.getInstance());
            url = new URL(url.toString() + '/'
                    + Common.fromFeedUrn(feed.getId()));
            ClientResponse response;
            if (contentId != null) {
                response = client.post(url.toString(),
                        new MultiPartRequestEntity(feed, content, contentId,
                                contentType));
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
                throw new IllegalArgumentException(response.getDocument()
                        .getRoot().toString());
            }
        } catch (MalformedURLException e) {
            log.error("push: bad url: " + url, e);
        }
        return null;
    }

    /**
     * Pushes entries from the specified feed to the remote services hosted at
     * the specified URL.
     * 
     * @param feed
     *            a feed containing entries
     * @param url
     *            a URL to a remote trsst service
     * @return a Feed returned by the server successfully accepting the feed, or
     *         null if unsuccessful.
     */
    public Feed push(Feed feed, URL url) {
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
     * @throws contentKey
     */
    public Feed post(KeyPair signingKeys, KeyPair encryptionKeys,
            EntryOptions options, FeedOptions feedOptions) throws IOException,
            SecurityException, GeneralSecurityException, Exception {
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
                Common.toX509FromPublicKey(encryptionKeys.getPublic()));
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

        // holds any attachments (can be used for logo and icons)
        String[] contentIds = new String[options.getContentCount()];

        // subject or verb or attachment is required to create an entry
        Entry entry = null;
        if (options.status != null || options.verb != null
                || contentIds.length > 0) {

            // create the new entry
            entry = Abdera.getInstance().newEntry();
            entry.setUpdated(feed.getUpdated());
            entry.setId(Common.toEntryUrn(feedId, feed.getUpdated().getTime()));
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
                // was: entry.setSummary(options.body);
                entry.setSummary(options.body,
                        org.apache.abdera.model.Text.Type.HTML);
                // FIXME: some readers only show type=html
            }

            // generate proof-of-work stamp for this feed id and entry id
            Element stampElement = entry.addExtension(new QName(Common.NS_URI,
                    Common.STAMP));
            stampElement.setText(Crypto.computeStamp(Common.STAMP_BITS, entry
                    .getUpdated().getTime(), feedId));

            if (options.mentions != null) {
                for (String s : options.mentions) {
                    entry.addCategory(Common.MENTION_URN, s, "Mention");
                    stampElement = entry.addExtension(new QName(Common.NS_URI,
                            Common.STAMP));
                    stampElement.setText(Crypto.computeStamp(Common.STAMP_BITS,
                            entry.getUpdated().getTime(), s));
                    // stamp is required for each mention
                }
            }
            if (options.tags != null) {
                for (String s : options.tags) {
                    entry.addCategory(Common.TAG_URN, s, "Tag");
                    stampElement = entry.addExtension(new QName(Common.NS_URI,
                            Common.STAMP));
                    stampElement.setText(Crypto.computeStamp(Common.STAMP_BITS,
                            entry.getUpdated().getTime(), s));
                    // stamp is required for each tag
                }
            }

            // generate an AES256 key for encrypting
            byte[] contentKey = null;
            if (options.recipientIds != null) {
                contentKey = Crypto.generateAESKey();
            }

            // for each content part
            for (int part = 0; part < contentIds.length; part++) {
                byte[] currentContent = options.getContentData()[part];
                String currentType = options.getMimetypes()[part];

                // encrypt before hashing if necessary
                if (contentKey != null) {
                    currentContent = Crypto.encryptAES(currentContent,
                            contentKey);
                }

                // calculate digest to determine content id
                byte[] digest = Common.ripemd160(currentContent);
                contentIds[part] = new Base64(0, null, true)
                        .encodeToString(digest);

                // add mime-type hint to content id (if not encrypted):
                // (some readers like to see a file extension on enclosures)
                if (currentType != null && contentKey == null) {
                    String extension = "";
                    int i = currentType.lastIndexOf('/');
                    if (i != -1) {
                        extension = '.' + currentType.substring(i + 1);
                    }
                    contentIds[part] = contentIds[part] + extension;
                }

                // set the content element
                if (entry.getContentSrc() == null) {
                    // only point to the first attachment if multiple
                    entry.setContent(new IRI(contentIds[part]), currentType);
                }

                // use a base uri so src attribute is simpler to process
                entry.getContentElement().setBaseUri(
                        Common.toEntryIdString(entry.getId()) + '/');
                entry.getContentElement().setAttributeValue(
                        new QName(Common.NS_URI, "hash", "trsst"), "ripemd160");

                // if not encrypted
                if (contentKey == null) {
                    // add an enclosure link
                    entry.addLink(Common.toEntryIdString(entry.getId()) + '/'
                            + contentIds[part], Link.REL_ENCLOSURE,
                            currentType, null, null, currentContent.length);
                }

            }

            if (contentIds.length == 0 && options.url != null) {
                Content content = Abdera.getInstance().getFactory()
                        .newContent();
                if (options.url.startsWith("urn:feed:")
                        || options.url.startsWith("urn:entry:")) {
                    content.setMimeType("application/atom+xml");
                }
                content.setSrc(options.url);
                entry.setContentElement(content);
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

            if (options.recipientIds == null) {
                // public post
                entry.setRights(Common.RIGHTS_NDBY_REVOCABLE);
            } else {
                // private post
                entry.setRights(Common.RIGHTS_RESERVED);
                try {
                    StringWriter stringWriter = new StringWriter();
                    StreamWriter writer = Abdera.getInstance()
                            .getWriterFactory().newStreamWriter();
                    writer.setWriter(stringWriter);
                    writer.startEntry();
                    writer.writeId(entry.getId());
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
                            writer.writeTitle(""); // empty title
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
                        if (options.publicOptions.tags != null) {
                            for (String s : options.publicOptions.tags) {
                                writer.writeCategory(s);
                            }
                        }
                        if (options.publicOptions.mentions != null) {
                            for (String s : options.publicOptions.mentions) {
                                writer.startElement("mention", Common.NS_URI,
                                        "trsst");
                                writer.writeElementText(s);
                                writer.endElement();
                            }
                        }
                    } else {
                        writer.writeTitle(""); // empty title
                    }
                    
                    writer.startContent("application/xenc+xml");

                    List<PublicKey> keys = new LinkedList<PublicKey>();
                    for (String id : options.recipientIds) {
                        // for each recipient
                        Feed recipientFeed = pull(id);
                        if (recipientFeed != null) {
                            // fetch encryption key
                            Element e = recipientFeed.getExtension(new QName(
                                    Common.NS_URI, Common.ENCRYPT));
                            if (e == null) {
                                // fall back to signing key
                                e = recipientFeed.getExtension(new QName(
                                        Common.NS_URI, Common.SIGN));
                            }
                            keys.add(Common.toPublicKeyFromX509(e.getText()));
                        }
                    }

                    // enforce the convention:
                    keys.remove(encryptionKeys.getPublic());
                    // move to end if exists;
                    // last encrypted key is for ourself
                    keys.add(encryptionKeys.getPublic());

                    // encrypt content key separately for each recipient
                    for (PublicKey recipient : keys) {
                        byte[] bytes = Crypto.encryptKeyWithECDH(contentKey,
                                feed.getUpdated().getTime(), recipient,
                                encryptionKeys.getPrivate());
                        String encoded = new Base64(0, null, true)
                                .encodeToString(bytes);
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
                    }

                    // now: encrypt the payload with content key
                    byte[] bytes = encryptElementAES(entry, contentKey);
                    String encoded = new Base64(0, null, true)
                            .encodeToString(bytes);
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

                    // done with encrypted elements
                    writer.endContent();
                    writer.endEntry();
                    writer.flush();
                    // this constructed entry now replaces the encrypted
                    // entry
                    entry = (Entry) Abdera.getInstance().getParserFactory()
                            .getParser()
                            .parse(new StringReader(stringWriter.toString()))
                            .getRoot();
                    // System.out.println(stringWriter.toString());
                } catch (Throwable t) {
                    log.error("Unexpected error while encrypting, exiting: "
                            + options.recipientIds, t);
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
        } else {
            log.info("No valid entries detected; updating feed.");
        }

        // remove existing feed signature element if any
        signatureElement = feed.getFirstChild(new QName(
                "http://www.w3.org/2000/09/xmldsig#", "Signature"));
        if (signatureElement != null) {
            signatureElement.discard();
        }

        // remove all navigation links before signing
        for (Link link : feed.getLinks()) {
            if (Link.REL_FIRST.equals(link.getRel())
                    || Link.REL_LAST.equals(link.getRel())
                    || Link.REL_CURRENT.equals(link.getRel())
                    || Link.REL_NEXT.equals(link.getRel())
                    || Link.REL_PREVIOUS.equals(link.getRel())) {
                link.discard();
            }
        }

        // remove all opensearch elements before signing
        for (Element e : feed
                .getExtensions("http://a9.com/-/spec/opensearch/1.1/")) {
            e.discard();
        }

        // set logo and/or icon
        if (contentIds.length > 0) {
            String url = Common.toEntryIdString(entry.getId()) + '/'
                    + contentIds[0];
            if (feedOptions.asIcon) {
                feed.setIcon(url);
            }
            if (feedOptions.asLogo) {
                feed.setLogo(url);
            }
        }

        // set base
        if (feedOptions.base != null) {
            String uri = feedOptions.base;
            if (!uri.endsWith("/")) {
                uri = uri + '/';
            }
            uri = uri + feedId;
            feed.setBaseUri(uri);
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
        if (contentIds.length > 0) {
            return push(feed, contentIds, options.getMimetypes(),
                    options.getContentData(), serving);
        }
        return push(feed, serving);
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

    public static byte[] encryptElementAES(Element element, byte[] secretKey)
            throws SecurityException {
        byte[] after = null;
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            element.writeTo(output);
            byte[] before = output.toByteArray();
            after = Crypto.encryptAES(before, secretKey);
        } catch (Exception e) {
            log.error("Error while encrypting element", e);
            throw new SecurityException(e);
        }
        return after;
    }

    public static Element decryptElementAES(byte[] data, byte[] secretKey)
            throws SecurityException {
        Element result;

        try {
            byte[] after = Crypto.decryptAES(data, secretKey);
            ByteArrayInputStream input = new ByteArrayInputStream(after);
            result = Abdera.getInstance().getParser().parse(input).getRoot();
        } catch (Exception e) {
            log.error("Error while decrypting: ", e);
            throw new SecurityException(e);
        }
        return result;
    }

    private final static org.slf4j.Logger log = org.slf4j.LoggerFactory
            .getLogger(Client.class);
}
