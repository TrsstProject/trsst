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

import java.io.UnsupportedEncodingException;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.text.SimpleDateFormat;
import java.util.Date;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;

import org.bouncycastle.crypto.BufferedBlockCipher;
import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.agreement.ECDHBasicAgreement;
import org.bouncycastle.crypto.digests.SHA1Digest;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.engines.IESEngine;
import org.bouncycastle.crypto.generators.KDF2BytesGenerator;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.modes.CBCBlockCipher;
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPrivateKey;
import org.bouncycastle.jcajce.provider.asymmetric.ec.BCECPublicKey;
import org.bouncycastle.jcajce.provider.asymmetric.ec.IESCipher;

/**
 * Shared utilities to try to keep the cryptography implementation in one place
 * for easier review.
 * 
 * @author mpowers
 */
public class Crypto {

    public static byte[] encryptKeyWithIES(byte[] input, long entryId,
            PublicKey publicKey, PrivateKey privateKey)
            throws GeneralSecurityException {
        try {
            // BC appears to be happier with BCECPublicKeys:
            // see BC's IESCipher.engineInit's check for ECPublicKey
            publicKey = new BCECPublicKey((ECPublicKey) publicKey, null);

            return _cryptIES(input, publicKey, true);
        } catch (GeneralSecurityException e) {
            log.error("Error while encrypting key", e);
            throw e;
        }
    }

    public static byte[] decryptKeyWithIES(byte[] input, long entryId,
            PublicKey publicKey, PrivateKey privateKey)
            throws GeneralSecurityException {
        try {
            // BC appears to be happier with BCECPrivateKeys:
            privateKey = new BCECPrivateKey((ECPrivateKey) privateKey, null);

            return _cryptIES(input, privateKey, false);
        } catch (GeneralSecurityException e) {
            log.error("Error while decrypting key", e);
            throw new GeneralSecurityException(e);
        }
    }

    private static byte[] _cryptIES(byte[] input, Key recipient,
            boolean forEncryption) throws InvalidKeyException,
            IllegalBlockSizeException, BadPaddingException {
        IESCipher cipher = new IESCipher(new IESEngine(
                new ECDHBasicAgreement(), new KDF2BytesGenerator(
                        new SHA1Digest()), new HMac(new SHA256Digest()),
                new PaddedBufferedBlockCipher(new CBCBlockCipher(
                        new AESEngine()))));

        cipher.engineInit(forEncryption ? Cipher.ENCRYPT_MODE
                : Cipher.DECRYPT_MODE, recipient, new SecureRandom());
        return cipher.engineDoFinal(input, 0, input.length);
    }

    public static byte[] generateAESKey() {
        byte[] result = new byte[32];
        new SecureRandom().nextBytes(result);
        return result;
    }

    public static byte[] encryptAES(byte[] input, byte[] key)
            throws InvalidCipherTextException {
        return _cryptBytesAES(input, key, true);
    }

    public static byte[] decryptAES(byte[] input, byte[] key)
            throws InvalidCipherTextException {
        return _cryptBytesAES(input, key, false);
    }

    // h/t Steve Weis, Michael Rogers, and liberationtech
    private static byte[] _cryptBytesAES(byte[] input, byte[] key,
            boolean forEncryption) throws InvalidCipherTextException {
        assert key.length == 32; // 32 bytes == 256 bits
        return process(input, new PaddedBufferedBlockCipher(new CBCBlockCipher(
                new AESEngine())), new KeyParameter(key), forEncryption);
        // note: using zero IV because we generate a new key for every message
    }

    // h/t Adam Paynter http://stackoverflow.com/users/41619/
    private static byte[] process(byte[] input,
            BufferedBlockCipher bufferedBlockCipher,
            CipherParameters cipherParameters, boolean forEncryption)
            throws InvalidCipherTextException {
        bufferedBlockCipher.init(forEncryption, cipherParameters);

        int inputOffset = 0;
        int inputLength = input.length;

        int maximumOutputLength = bufferedBlockCipher
                .getOutputSize(inputLength);
        byte[] output = new byte[maximumOutputLength];
        int outputOffset = 0;
        int outputLength = 0;

        int bytesProcessed;

        bytesProcessed = bufferedBlockCipher.processBytes(input, inputOffset,
                inputLength, output, outputOffset);
        outputOffset += bytesProcessed;
        outputLength += bytesProcessed;

        bytesProcessed = bufferedBlockCipher.doFinal(output, outputOffset);
        outputOffset += bytesProcessed;
        outputLength += bytesProcessed;

        if (outputLength == output.length) {
            return output;
        } else {
            byte[] truncatedOutput = new byte[outputLength];
            System.arraycopy(output, 0, truncatedOutput, 0, outputLength);
            return truncatedOutput;
        }
    }

    /**
     * Computes hashcash proof-of-work stamp for the given input and
     * bitstrength. Servers can choose which bitstrength they accept, but we
     * recommend at least 20. The colon ":" is a delimiter in hashcash so we
     * replace all occurances in a token with ".".
     * 
     * This machine is calculating stamps at a mean rate of 340ms, 694ms,
     * 1989ms, 4098ms, and 6563ms for bits of 19, 20, 21, 22, and 23
     * respectively.
     * 
     * @param bitstrength
     *            number of leading zero bits to find
     * @param timestamp
     *            the timestamp/entry-id of the enclosing entry
     * @param token
     *            a feed-id or mention-id or tag
     * @return
     */
    public static final String computeStamp(int bitstrength, long timestamp,
            String token) {
        try {
            if (token.indexOf(':') != -1) {
                token = token.replace(":", ".");
            }
            String formattedDate = new SimpleDateFormat("YYMMdd")
                    .format(new Date(timestamp));
            String prefix = "1:" + Integer.toString(bitstrength) + ":"
                    + formattedDate + ":" + token + "::"
                    + Long.toHexString(timestamp) + ":";
            int masklength = bitstrength / 8;
            byte[] prefixBytes = prefix.getBytes("UTF-8");
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");

            int i;
            int b;
            byte[] hash;
            long counter = 0;
            while (true) {
                sha1.update(prefixBytes);
                sha1.update(Long.toHexString(counter).getBytes());
                hash = sha1.digest(); // 20 bytes long
                for (i = 0; i < 20; i++) {
                    b = (i < masklength) ? 0 : 255 >> (bitstrength % 8);
                    if (b != (b | hash[i])) {
                        // no match; keep trying
                        break;
                    }
                    if (i == masklength) {
                        // we're a match: return the stamp
                        // System.out.println(Common.toHex(hash));
                        return prefix + Long.toHexString(counter);
                    }
                }
                counter++;
                // keep going forever until we find it
            }
        } catch (UnsupportedEncodingException e) {
            log.error("No string encoding found: ", e);
        } catch (NoSuchAlgorithmException e) {
            log.error("No hash algorithm found: ", e);
        }
        log.error("Exiting without stamp: should never happen");
        return null;
    }

    /**
     * Verifies the specified hashcash proof-of-work stamp for the given
     * timestamp and token.
     * 
     * @return true if verified, false if failed or invalid.
     */
    public static final boolean verifyStamp(String stamp, long timestamp,
            String token) {
        String[] fields = stamp.split(":");

        if (fields.length != 7) {
            log.info("verifyStamp: invalid number of fields: " + fields.length);
            return false;
        }

        if (!"1".equals(fields[0])) {
            log.info("verifyStamp: invalid version: " + fields[0]);
            return false;
        }

        int bitstrength;
        try {
            bitstrength = Integer.parseInt(fields[1]);
        } catch (NumberFormatException e) {
            log.info("verifyStamp: invalid bit strength: " + fields[1]);
            return false;
        }

        String formattedDate = new SimpleDateFormat("YYMMdd").format(new Date(
                timestamp));
        if (!formattedDate.equals(fields[2])) {
            log.info("verifyStamp: invalid date: " + fields[2]);
            return false;
        }

        if (!token.equals(fields[3])) {
            log.info("verifyStamp: invalid token: " + fields[3]);
            return false;
        }

        // other fields are ignored;
        // now verify hash:
        try {
            int b;
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(
                    stamp.getBytes("UTF-8"));
            for (int i = 0; i < 20; i++) {
                b = (i < bitstrength / 8) ? 0 : 255 >> (bitstrength % 8);
                if (b != (b | hash[i])) {
                    return false;
                }
                if (i == bitstrength / 8) {
                    // stamp is verified
                    return true;
                }
            }
        } catch (UnsupportedEncodingException e) {
            log.error("No string encoding found: ", e);
        } catch (NoSuchAlgorithmException e) {
            log.error("No hash algorithm found: ", e);
        }

        return false;
    }

    private final static org.slf4j.Logger log = org.slf4j.LoggerFactory
            .getLogger(Crypto.class);
}
