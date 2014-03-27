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
package com.trsst;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyFactory;
import java.security.KeyManagementException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.abdera.Abdera;
import org.apache.abdera.model.Document;
import org.apache.abdera.model.Element;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.httpclient.protocol.Protocol;
import org.apache.commons.httpclient.protocol.ProtocolSocketFactory;
import org.apache.commons.lang3.StringEscapeUtils;
import org.bouncycastle.crypto.digests.RIPEMD160Digest;
import org.bouncycastle.util.encoders.Hex;
import org.w3c.tidy.Tidy;

import com.trsst.client.AnonymSSLSocketFactory;

/**
 * Shared utilities and constants used by both clients and servers. Portions
 * borrowed from bitsofproof, abdera, and apache commons.
 * 
 * @author mpowers
 */
public class Common {
    public static final String ROOT_ALIAS = "home";
    public static final String FEED_URN_PREFIX = "urn:feed:";
    public static final String ENTRY_URN_PREFIX = "urn:entry:";
    public static final String URN_SEPARATOR = ":";
    public static final String CURVE_NAME = "secp256k1";
    public static final String NS_URI = "http://trsst.com/spec/0.1";
    public static final String NS_ABBR = "trsst";
    public static final String SIGN = "sign";
    public static final String ENCRYPT = "encrypt";
    public static final String MENTION_URN = "urn:com.trsst.mention";
    public static final String TAG_URN = "urn:com.trsst.tag";
    public static final String PREDECESSOR = "predecessor";
    public static final String ATTACHMENT_DIGEST = "digest";
    public static final String PREDECESSOR_ID = "id";
    public static final String KEY_EXTENSION = ".p12";
    public static final String VERB_DELETE = "delete";
    public static final String VERB_DELETED = "deleted";
    public static final String STAMP = "stamp";
    public static final int STAMP_BITS = 20;

    /**
     * Default public rights are like CC ND BY but with added right of
     * revocation. This lets you delete an entry and require takedown of that
     * entry whereever it has been distributed.
     */
    public static final String RIGHTS_NDBY_REVOCABLE = "attribution, no derivatives, revoked if deleted";
    // "You may copy, distribute, display and perform only verbatim copies of
    // the work, not derivative works based on it, and only if fully attributed
    // to the author. Your license to the work is revoked worldwide if the
    // author publicly deletes the original work.";

    /**
     * Default private rights are are explicity ARR if only to clearly
     * differentiate private posts from public ones.
     */
    public static final String RIGHTS_RESERVED = "all reserved";

    private final static org.slf4j.Logger log;

    static {
        log = org.slf4j.LoggerFactory.getLogger(Common.class);
        try {
            Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        } catch (Throwable t) {
            log.error(
                    "Could not initialize security provider: " + t.getMessage(),
                    t);
        }
    }

    /**
     * Hashes an elliptic curve public key into a shortened "satoshi-style"
     * string that we use for a publicly-readable account id. Borrowed from
     * bitsofproof.
     * 
     * @param key
     *            the account EC public key.
     * @return the account id
     */
    public static String toFeedId(PublicKey key) {
        byte[] keyDigest = keyHash(key.getEncoded());
        byte[] addressBytes = new byte[keyDigest.length + 4];
        // note: now leaving out BTC's first byte identifier
        System.arraycopy(keyDigest, 0, addressBytes, 0, keyDigest.length);
        byte[] check = hash(addressBytes, 0, keyDigest.length);
        System.arraycopy(check, 0, addressBytes, keyDigest.length, 4);
        return toBase58(addressBytes);
    }

    public static File getClientRoot() {
        String path = System.getProperty("user.home", ".");
        File root = new File(path, "trsstd");
        path = System.getProperty("com.trsst.client.storage");
        if (path != null) {
            try {
                root = new File(path);
                root.mkdirs();
            } catch (Throwable t) {
                System.err.println("Invalid path: " + path + " : "
                        + t.getMessage());
            }
        }
        return root;
    }

    public static File getServerRoot() {
        String path = System.getProperty("user.home", ".");
        File root = new File(path, "trsstd");
        path = System.getProperty("com.trsst.server.storage");
        if (path != null) {
            try {
                root = new File(path);
                root.mkdirs();
            } catch (Throwable t) {
                System.err.println("Invalid path: " + path + " : "
                        + t.getMessage());
            }
        }
        return root;
    }

    public static final String toFeedIdString(Object feedOrEntryUrn) {
        String feedId = feedOrEntryUrn.toString();
        if (feedId.startsWith(FEED_URN_PREFIX)) {
            feedId = feedId.substring(FEED_URN_PREFIX.length());
        } else if (feedId.startsWith(ENTRY_URN_PREFIX)) {
            feedId = feedId.substring(ENTRY_URN_PREFIX.length());
            feedId = feedId.substring(0, feedId.lastIndexOf(':'));
        }
        return feedId;
    }

    public static final String toEntryIdString(Object entryUrn) {
        String entryId = entryUrn.toString();
        int i = entryId.lastIndexOf(URN_SEPARATOR);
        if (i != -1) {
            entryId = entryId.substring(i + 1);
        }
        if (entryId.startsWith(ENTRY_URN_PREFIX)) {
            entryId = entryId.substring(ENTRY_URN_PREFIX.length());
        }
        return entryId;
    }

    public static final long toEntryId(Object entryUrn) {
        return Long.parseLong(toEntryIdString(entryUrn), 16);
    }

    public static final long generateEntryId() {
        try {
            // sleep to ensure a unique id
            // if creating multiple entries
            Thread.sleep(3);
        } catch (InterruptedException e) {
            // should never ever happen
            log.warn("generateEntryId: interrupted", e);
        }
        return System.currentTimeMillis();
    }

    public static final String toEntryUrn(String feedId, long entryId) {
        return ENTRY_URN_PREFIX + feedId + URN_SEPARATOR
                + Long.toHexString(entryId);
    }

    public static final String fromFeedUrn(Object feedUrn) {
        String feedId = feedUrn.toString();
        if (feedId.startsWith(FEED_URN_PREFIX)) {
            feedId = feedId.substring(9);
        }
        return feedId;
    }

    public static final String toFeedUrn(String feedId) {
        if (!feedId.startsWith(FEED_URN_PREFIX)) {
            feedId = FEED_URN_PREFIX + feedId;
        }
        // return as string to avoid uri try/catch
        return feedId;
    }

    public static final byte[] keyHash(byte[] key) {
        try {
            byte[] sha256 = MessageDigest.getInstance("SHA-256").digest(key);
            return ripemd160(sha256);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static final byte[] ripemd160(byte[] data) {
        byte[] ph = new byte[20];
        RIPEMD160Digest digest = new RIPEMD160Digest();
        digest.update(data, 0, data.length);
        digest.doFinal(ph, 0);
        return ph;
    }

    public static final byte[] hash(byte[] data, int offset, int len) {
        try {
            MessageDigest a = MessageDigest.getInstance("SHA-256");
            a.update(data, offset, len);
            return a.digest(a.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean isFeedId(String id) {
        if (id.startsWith(FEED_URN_PREFIX)) {
            id = id.substring(FEED_URN_PREFIX.length());
        }
        return (decodeChecked(id) != null);
    }

    public static boolean isExternalId(String id) {
        // "external id" a.k.a. URL
        try {
            // test for valid url
            new URL(decodeURL(id));
            return true;
        } catch (MalformedURLException e) {
            return false;
        }
    }

    /**
     * Uses the checksum in the last 4 bytes of the decoded data to verify the
     * rest are correct. The checksum is removed from the returned data. Returns
     * null if invalid. Borrowed from bitcoinj.
     */
    private static final byte[] decodeChecked(String input) {
        byte tmp[];
        try {
            tmp = fromBase58(input);
        } catch (IllegalArgumentException e) {
            log.trace("decodeChecked: could not decode: " + input);
            return null;
        }
        if (tmp.length < 4) {
            log.trace("decodeChecked: input too short: " + input);
            return null;
        }
        byte[] bytes = copyOfRange(tmp, 0, tmp.length - 4);
        byte[] checksum = copyOfRange(tmp, tmp.length - 4, tmp.length);

        tmp = doubleDigest(bytes, 0, bytes.length);
        byte[] hash = copyOfRange(tmp, 0, 4);
        if (!Arrays.equals(checksum, hash)) {
            log.trace("decodeChecked: checksum does not validate: " + input);
            return null;
        }
        log.trace("decodeChecked: input is valid: " + input);
        return bytes;
    }

    private static final byte[] copyOfRange(byte[] source, int from, int to) {
        byte[] range = new byte[to - from];
        System.arraycopy(source, from, range, 0, range.length);
        return range;
    }

    /**
     * Calculates the SHA-256 hash of the given byte range, and then hashes the
     * resulting hash again. This is standard procedure in Bitcoin. The
     * resulting hash is in big endian form. Borrowed from bitcoinj.
     */
    private static final byte[] doubleDigest(byte[] input, int offset,
            int length) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            log.error(
                    "Should never happen: could not find SHA-256 MD algorithm",
                    e);
            return null;
        }
        digest.reset();
        digest.update(input, offset, length);
        byte[] first = digest.digest();
        return digest.digest(first);
    }

    /**
     * Converts a X509-encoded EC key to a PublicKey.
     */
    public static PublicKey toPublicKeyFromX509(String stored)
            throws GeneralSecurityException {
        KeyFactory factory = KeyFactory.getInstance("EC");
        byte[] data = Base64.decodeBase64(stored);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(data);
        return factory.generatePublic(spec);

    }

    /**
     * Converts an EC PublicKey to an X509-encoded string.
     */
    public static String toX509FromPublicKey(PublicKey publicKey)
            throws GeneralSecurityException {
        KeyFactory factory = KeyFactory.getInstance("EC");
        X509EncodedKeySpec spec = factory.getKeySpec(publicKey,
                X509EncodedKeySpec.class);
        return new Base64(0, null, true).encodeToString(spec.getEncoded());
    }

    static final KeyPair generateSigningKeyPair() {
        try {
            KeyPairGenerator kpg;
            // kpg = KeyPairGenerator.getInstance("EC", "BC");
            kpg = new org.bouncycastle.jcajce.provider.asymmetric.ec.KeyPairGeneratorSpi.EC();
            kpg.initialize(new ECGenParameterSpec(CURVE_NAME));
            KeyPair kp = kpg.generateKeyPair();
            return kp;
            // } catch (NoSuchAlgorithmException e) {
            // log.error("Error while generating key: " + e.getMessage(), e);
            // } catch (NoSuchProviderException e) {
            // log.error("Error while generating key: " + e.getMessage(), e);
        } catch (InvalidAlgorithmParameterException e) {
            log.error("Error while generating key: " + e.getMessage(), e);
        }
        return null;
    }

    static final KeyPair generateEncryptionKeyPair() {
        try {
            KeyPairGenerator kpg;
            // kpg = KeyPairGenerator.getInstance("EC", "BC");
            kpg = new org.bouncycastle.jcajce.provider.asymmetric.ec.KeyPairGeneratorSpi.EC();
            kpg.initialize(new ECGenParameterSpec(CURVE_NAME));
            KeyPair kp = kpg.generateKeyPair();
            return kp;
            // } catch (NoSuchAlgorithmException e) {
            // log.error("Error while generating key: " + e.getMessage(), e);
            // } catch (NoSuchProviderException e) {
            // e.printStackTrace();
        } catch (InvalidAlgorithmParameterException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static final char[] b58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
            .toCharArray();
    private static final int[] r58 = new int[256];
    static {
        for (int i = 0; i < 256; ++i) {
            r58[i] = -1;
        }
        for (int i = 0; i < b58.length; ++i) {
            r58[b58[i]] = i;
        }
    }

    public static String toBase58(byte[] b) {
        if (b.length == 0) {
            return "";
        }

        int lz = 0;
        while (lz < b.length && b[lz] == 0) {
            ++lz;
        }

        StringBuffer s = new StringBuffer();
        BigInteger n = new BigInteger(1, b);
        while (n.compareTo(BigInteger.ZERO) > 0) {
            BigInteger[] r = n.divideAndRemainder(BigInteger.valueOf(58));
            n = r[0];
            char digit = b58[r[1].intValue()];
            s.append(digit);
        }
        while (lz > 0) {
            --lz;
            s.append("1");
        }
        return s.reverse().toString();
    }

    public static String toBase58WithChecksum(byte[] b) {
        byte[] cs = Common.hash(b, 0, b.length);
        byte[] extended = new byte[b.length + 4];
        System.arraycopy(b, 0, extended, 0, b.length);
        System.arraycopy(cs, 0, extended, b.length, 4);
        return toBase58(extended);
    }

    public static byte[] fromBase58WithChecksum(String s) {
        byte[] b = fromBase58(s);
        if (b.length < 4) {
            throw new IllegalArgumentException("Too short for checksum " + s);
        }
        byte[] cs = new byte[4];
        System.arraycopy(b, b.length - 4, cs, 0, 4);
        byte[] data = new byte[b.length - 4];
        System.arraycopy(b, 0, data, 0, b.length - 4);
        byte[] h = new byte[4];
        System.arraycopy(hash(data, 0, data.length), 0, h, 0, 4);
        if (Arrays.equals(cs, h)) {
            return data;
        }
        throw new IllegalArgumentException("Checksum mismatch " + s);
    }

    public static byte[] fromBase58(String s) {
        try {
            boolean leading = true;
            int lz = 0;
            BigInteger b = BigInteger.ZERO;
            for (char c : s.toCharArray()) {
                if (leading && c == '1') {
                    ++lz;
                } else {
                    leading = false;
                    b = b.multiply(BigInteger.valueOf(58));
                    b = b.add(BigInteger.valueOf(r58[c]));
                }
            }
            byte[] encoded = b.toByteArray();
            if (encoded[0] == 0) {
                if (lz > 0) {
                    --lz;
                } else {
                    byte[] e = new byte[encoded.length - 1];
                    System.arraycopy(encoded, 1, e, 0, e.length);
                    encoded = e;
                }
            }
            byte[] result = new byte[encoded.length + lz];
            System.arraycopy(encoded, 0, result, lz, encoded.length);

            return result;
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new IllegalArgumentException("Invalid character in address");
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static byte[] reverse(byte[] data) {
        for (int i = 0, j = data.length - 1; i < data.length / 2; i++, j--) {
            data[i] ^= data[j];
            data[j] ^= data[i];
            data[i] ^= data[j];
        }
        return data;
    }

    public static String toHex(byte[] data) {
        try {
            return new String(Hex.encode(data), "US-ASCII");
        } catch (UnsupportedEncodingException e) {
        }
        return null;
    }

    public static byte[] fromHex(String hex) {
        return Hex.decode(hex);
    }

    public static boolean isLessThanUnsigned(long n1, long n2) {
        return (n1 < n2) ^ ((n1 < 0) != (n2 < 0));
    }

    public static byte[] readFully(InputStream data) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int c;
        byte[] buf = new byte[1024];
        while ((c = data.read(buf)) != -1) {
            output.write(buf, 0, c);
        }
        return output.toByteArray();
    }

    public static String encodeURL(String parameter) {
        try {
            return URLEncoder.encode(parameter, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            log.error("encodeURL: should never happen", e);
            return null;
        }
    }

    public static String decodeURL(String parameter) {
        try {
            return URLDecoder.decode(parameter, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            log.error("encodeURL: should never happen", e);
            return null;
        }
    }

    public static String escapeHTML(String html) {
        return StringEscapeUtils.escapeHtml3(html);
    }

    public static String unescapeHTML(String escapedHtml) {
        return StringEscapeUtils.unescapeHtml3(escapedHtml);
    }

    public static org.w3c.dom.Document fomToDom(Document<Element> doc) {
        org.w3c.dom.Document dom = null;
        if (doc != null) {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                doc.writeTo(out);
                ByteArrayInputStream in = new ByteArrayInputStream(
                        out.toByteArray());
                DocumentBuilderFactory dbf = DocumentBuilderFactory
                        .newInstance();
                dbf.setValidating(false);
                dbf.setNamespaceAware(true);
                DocumentBuilder db = dbf.newDocumentBuilder();
                dom = db.parse(in);
            } catch (Exception e) {
            }
        }
        return dom;
    }

    public static Document<Element> domToFom(org.w3c.dom.Document dom) {
        Document<Element> doc = null;
        if (dom != null) {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                TransformerFactory tf = TransformerFactory.newInstance();
                Transformer t = tf.newTransformer();
                t.transform(new DOMSource(dom), new StreamResult(out));
                ByteArrayInputStream in = new ByteArrayInputStream(
                        out.toByteArray());
                doc = Abdera.getInstance().getParser().parse(in);
            } catch (Exception e) {
            }
        }
        return doc;
    }

    public static org.w3c.dom.Element fomToDom(Element element) {
        org.w3c.dom.Element dom = null;
        if (element != null) {
            try {
                ByteArrayInputStream in = new ByteArrayInputStream(element
                        .toString().getBytes());
                DocumentBuilderFactory dbf = DocumentBuilderFactory
                        .newInstance();
                dbf.setValidating(false);
                dbf.setNamespaceAware(true);
                DocumentBuilder db = dbf.newDocumentBuilder();
                dom = db.parse(in).getDocumentElement();
            } catch (Exception e) {
            }
        }
        return dom;
    }

    public static Element domToFom(org.w3c.dom.Element element) {
        Element el = null;
        if (element != null) {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                TransformerFactory tf = TransformerFactory.newInstance();
                Transformer t = tf.newTransformer();
                t.transform(new DOMSource(element), new StreamResult(out));
                ByteArrayInputStream in = new ByteArrayInputStream(
                        out.toByteArray());
                el = Abdera.getInstance().getParser().parse(in).getRoot();
            } catch (Exception e) {
            }
        }
        return el;
    }

    public static String formatXML(String xml) {
        Tidy tidy = new Tidy();
        tidy.setXmlTags(true);
        tidy.setXmlOut(true);
        StringWriter writer = new StringWriter();
        tidy.parse(new StringReader(xml), writer);
        return writer.toString();
    }

    public static Attributes getManifestAttributes() {
        Attributes result = null;
        Class<Common> clazz = Common.class;
        String className = clazz.getSimpleName() + ".class";
        URL classPath = clazz.getResource(className);
        if (classPath == null || !classPath.toString().startsWith("jar")) {
            // Class not from JAR
            return null;
        }
        String classPathString = classPath.toString();
        String manifestPath = classPathString.substring(0,
                classPathString.lastIndexOf("!") + 1) + "/META-INF/MANIFEST.MF";
        try {
            Manifest manifest = new Manifest(new URL(manifestPath).openStream());
            result = manifest.getMainAttributes();
        } catch (MalformedURLException e) {
            log.error("Could not locate manifest: " + manifestPath);
        } catch (IOException e) {
            log.error("Could not open manifest: " + manifestPath);
        }
        return result;
    }

    public static Date getBuildDate() {
        Date result = null;
        Attributes attributes = getManifestAttributes();
        if (attributes != null) {
            String dateString = attributes.getValue("Built-On");
            try {
                result = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                        .parse(dateString);
            } catch (Throwable t) {
                log.warn("Could not parse build timestamp: " + dateString);
            }
        } else {
            log.warn("Could not find manifest attributes.");
        }
        return result;
    }

    public static String getBuildId() {
        Attributes attributes = getManifestAttributes();
        if (attributes != null) {
            return attributes.getValue("Implementation-Build");
        } else {
            log.warn("Could not find manifest attributes.");
        }
        return null;
    }

    public static String getBuildString() {
        String result = null;
        String[] keys = new String[] { "Implementation-Title",
                "Implementation-Version", "Implementation-Build", "Built-On", };
        Attributes attributes = getManifestAttributes();
        if (attributes != null) {
            Object value;
            for (String key : keys) {
                value = attributes.getValue(key);
                if (value != null) {
                    if (result == null) {
                        result = value.toString();
                    } else {
                        result = result + ' ' + value.toString();
                    }
                }
            }
        } else {
            result = "trsst client";
        }
        return result;
    }

    /**
     * Most trsst nodes run with self-signed certificates, so by default we
     * accept them. While posts are still signed and/or encrypted, a MITM can
     * still refuse our out-going posts and suppress incoming new ones, but this
     * the reason to relay with many trsst servers. Use the -strict option to
     * require CA-signed certificates. Note that nowadays CA-signed certs are no
     * guarantee either.
     */
    public static void enableAnonymousSSL() {
        TrustManager[] trustAllCerts = new TrustManager[] { new X509TrustManager() {
            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return null;
            }

            public void checkClientTrusted(X509Certificate[] certs,
                    String authType) {
            }

            public void checkServerTrusted(X509Certificate[] certs,
                    String authType) {
            }

        } };

        SSLContext sc;
        try {
            sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection
                    .setDefaultSSLSocketFactory(sc.getSocketFactory());
        } catch (NoSuchAlgorithmException e) {
            log.error("Can't get SSL context", e);
        } catch (KeyManagementException e) {
            log.error("Can't set SSL socket factory", e);
        }

        // Create all-trusting host name verifier
        HostnameVerifier allHostsValid = new HostnameVerifier() {
            public boolean verify(String hostname, SSLSession session) {
                return true;
            }
        };

        // Install the all-trusting host verifier
        HttpsURLConnection.setDefaultHostnameVerifier(allHostsValid);

        // For apache http client
        Protocol anonhttps = new Protocol("https",
                (ProtocolSocketFactory) new AnonymSSLSocketFactory(), 443); //
        Protocol.registerProtocol("https", anonhttps);        
    }
}
