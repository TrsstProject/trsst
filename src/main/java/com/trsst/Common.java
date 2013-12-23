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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Security;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

import org.apache.commons.codec.binary.Base64;
import org.bouncycastle.crypto.digests.RIPEMD160Digest;
import org.bouncycastle.util.encoders.Hex;

/**
 * Shared utilities and constants used by both clients and servers. Portions
 * borrowed from bitsofproof, abdera, and apache commons.
 * 
 * @author mpowers
 */
public class Common {
    public static final String CURVE_NAME = "secp256k1";
    public static final String NS_URI = "http://trsst.com/spec/0.1";
    public static final String NS_ABBR = "trsst";
    public static final String SIGN = "sign";
    public static final String ENCRYPT = "encrypt";
    public static final String PREDECESSOR = "predecessor";
    public static final String ATTACHMENT_DIGEST = "digest";
    public static final String PREDECESSOR_ID = "id";

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
        byte[] addressBytes = new byte[1 + keyDigest.length + 4];
        // leave first byte as zero for bitcoin common production network
        System.arraycopy(keyDigest, 0, addressBytes, 1, keyDigest.length);
        byte[] check = hash(addressBytes, 0, keyDigest.length + 1);
        System.arraycopy(check, 0, addressBytes, keyDigest.length + 1, 4);
        return toBase58(addressBytes);
    }

    public static final String fromEntryUrn(Object entryUrn) {
        String entryId = entryUrn.toString();
        if (entryId.startsWith("urn:uuid:")) {
            entryId = entryId.substring(9);
        }
        return entryId;
    }

    public static final String toEntryUrn(String entryId) {
        if (!entryId.startsWith("urn:uuid:")) {
            entryId = "urn:uuid:" + entryId;
        }
        // return as string to avoid uri try/catch
        return entryId;
    }

    public static final String fromFeedUrn(Object feedUrn) {
        String feedId = feedUrn.toString();
        if (feedId.startsWith("urn:feed:")) {
            feedId = feedId.substring(9);
        }
        return feedId;
    }

    public static final String toFeedUrn(String feedId) {
        if (!feedId.startsWith("urn:feed:")) {
            feedId = "urn:feed:" + feedId;
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

    public static boolean isAccountId(String id) {
        return (decodeChecked(id) != null);
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

}
