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
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;

import javax.crypto.KeyAgreement;

import org.apache.abdera.security.SecurityException;
import org.bouncycastle.crypto.BlockCipher;
import org.bouncycastle.crypto.BufferedBlockCipher;
import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.paddings.BlockCipherPadding;
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher;
import org.bouncycastle.crypto.paddings.ZeroBytePadding;
import org.bouncycastle.crypto.params.KeyParameter;

/**
 * Shared utilities to try to keep the cryptography implementation in one place
 * for easier review.
 * 
 * @author mpowers
 */
public class Crypto {

    /**
     * Takes the specified 32 bytes, appends its sha-256 digest, and xor
     * encrypts those 64 bytes with the sha-512 hash of the ECDH shared secret
     * and the entry id.
     * 
     * @param input
     *            32 byte key to be encrypted
     * @param publicKey
     * @param privateKey
     * @return
     * @throws SecurityException
     *             if unexpected error
     */
    public static byte[] encryptKeyWithECDH(byte[] input, long entryId,
            PublicKey publicKey, PrivateKey privateKey)
            throws SecurityException {
        assert input.length == 32; // 256 bit key
        byte[] result = null;
        try {
            KeyAgreement keyAgreement = KeyAgreement.getInstance("ECDH", "BC");
            keyAgreement.init(privateKey);
            keyAgreement.doPhase(publicKey, true);
            byte[] sharedSecret = keyAgreement.generateSecret();

            // generate 512 bits using shared secret and entry id
            MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
            sha512.update(sharedSecret);
            sha512.update(ByteBuffer.allocate(8).putLong(entryId));
            byte[] sharedHash = sha512.digest();

            // calculate a digest of the input
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input);

            // xor the key and the digest against the shared hash
            int i;
            result = new byte[64];
            for (i = 0; i < 32; i++) {
                result[i] = (byte) (input[i] ^ sharedHash[i]);
            }
            for (i = 0; i < 32; i++) {
                result[i + 32] = (byte) (digest[i] ^ sharedHash[i + 32]);
            }
        } catch (Exception e) {
            log.error("Error while encrypting element", e);
            throw new SecurityException(e);
        }
        return result;
    }

    /**
     * Takes the specified 64 byte encoded input and xor decrypts it with the
     * sha-512 hash of the ECDH shared secret and the entry id. Then checks to
     * see if the last 32 bytes is the sha-256 hash of the first 32 bytes. If
     * so, returns the first 32 bytes of the decrypted content. Otherwise, then
     * this key was not intended for us, and returns null.
     * 
     * @param input
     *            64 byte input to be decrypted
     * @param publicKey
     * @param privateKey
     * @return the original 32 byte input, or null if unintended recipient.
     * @throws SecurityException
     *             if unexpected error
     */
    public static byte[] decryptKeyWithECDH(byte[] input, long entryId,
            PublicKey publicKey, PrivateKey privateKey)
            throws SecurityException {
        assert input.length == 64; // 512 bit encrypted key
        try {
            KeyAgreement keyAgreement = KeyAgreement.getInstance("ECDH", "BC");
            keyAgreement.init(privateKey);
            keyAgreement.doPhase(publicKey, true);
            byte[] sharedSecret = keyAgreement.generateSecret();

            // generate 512 bits using shared secret and entry id
            MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
            sha512.update(sharedSecret);
            sha512.update(ByteBuffer.allocate(8).putLong(entryId));
            byte[] sharedHash = sha512.digest();

            // xor the key and the digest against the shared hash
            int i;
            byte[] decoded = new byte[64];
            for (i = 0; i < 64; i++) {
                decoded[i] = (byte) (input[i] ^ sharedHash[i]);
            }

            // calculate digest of the decoded key
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(decoded, 0, 32);
            byte[] digest = sha256.digest();

            // verify that the digest of first 32 bytes matches last 32 bytes
            for (i = 0; i < 32; i++) {
                if (digest[i] != decoded[i + 32]) {
                    // incorrectly decoded: we're not the intended recipient
                    return null;
                }
            }
            return Arrays.copyOfRange(decoded, 0, 32);
        } catch (Exception e) {
            log.error("Error while decrypting element", e);
            throw new SecurityException(e);
        }
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

    private static byte[] _cryptBytesAES(byte[] input, byte[] key,
            boolean forEncryption) throws InvalidCipherTextException {
        assert key.length == 32; // 32 bytes == 256 bits
        CipherParameters cipherParameters = new KeyParameter(key);
        BlockCipher blockCipher = new AESEngine();
        BlockCipherPadding blockCipherPadding = new ZeroBytePadding();
        BufferedBlockCipher bufferedBlockCipher = new PaddedBufferedBlockCipher(
                blockCipher, blockCipherPadding);
        return process(input, bufferedBlockCipher, cipherParameters,
                forEncryption);
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
     * bitstrength. Servers can choose which bitstrength they accept, but
     * we recommend at least 20.  The colon ":" is a delimiter in hashcash
     * so we replace all occurances in a token with ".".  
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
            String formattedDate = new SimpleDateFormat("YYMMDD")
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

        String formattedDate = new SimpleDateFormat("YYMMDD").format(new Date(
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
