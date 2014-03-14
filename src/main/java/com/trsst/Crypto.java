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

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;

import org.apache.abdera.security.SecurityException;
import org.bouncycastle.crypto.BlockCipher;
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
import org.bouncycastle.crypto.paddings.BlockCipherPadding;
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher;
import org.bouncycastle.crypto.paddings.ZeroBytePadding;
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

    /**
     * Takes the specified 32 bytes, appends its sha-256 digest, and xor
     * encrypts those 64 bytes with the sha-512 hash of the ECDH shared secret
     * and the entry id.
     * 
     * @param input 32 byte key to be encrypted
     * @param publicKey
     * @param privateKey
     * @return
     * @throws SecurityException if unexpected error
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
//            System.out.println("sharedSecret:");
//            System.out.println(Common.toHex(sharedSecret));

            // generate 512 bits using shared secret and entry id
            MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
            sha512.update(sharedSecret);
            sha512.update(ByteBuffer.allocate(8).putLong(entryId));
            byte[] sharedHash = sha512.digest();
//            System.out.println("sharedHash:");
//            System.out.println(Common.toHex(sharedHash));

            // calculate a digest of the input
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input);

            // xor the key and the digest against the shared hash
//            System.out.println("input:");
//            System.out.println(Common.toHex(input));
            int i;
            result = new byte[64];
            for (i = 0; i < 32; i++) {
                result[i] = (byte) (input[i] ^ sharedHash[i]);
            }
            for (i = 0; i < 32; i++) {
                result[i + 32] = (byte) (digest[i] ^ sharedHash[i + 32]);
            }
//            System.out.println("encoded:");
//            System.out.println(Common.toHex(result));
//            System.out.println();
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
     * @param input 64 byte input to be decrypted
     * @param publicKey
     * @param privateKey
     * @return the original 32 byte input, or null if unintended recipient.
     * @throws SecurityException if unexpected error
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
//            System.out.println("sharedSecret:");
//            System.out.println(Common.toHex(sharedSecret));

            // generate 512 bits using shared secret and entry id
            MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
            sha512.update(sharedSecret);
            sha512.update(ByteBuffer.allocate(8).putLong(entryId));
            byte[] sharedHash = sha512.digest();
//            System.out.println("sharedHash:");
//            System.out.println(Common.toHex(sharedHash));

            // xor the key and the digest against the shared hash
//            System.out.println("input:");
//            System.out.println(Common.toHex(input));
            int i;
            byte[] decoded = new byte[64];
            for (i = 0; i < 64; i++) {
                decoded[i] = (byte) (input[i] ^ sharedHash[i]);
            }
//            System.out.println("decoded:");
//            System.out.println(Common.toHex(decoded));

            // calculate digest of the decoded key
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(decoded, 0, 32);
            byte[] digest = sha256.digest();
//            System.out.println("compared:");
//            System.out.print("                                ");
//            System.out.print("                                ");
//            System.out.println(Common.toHex(digest));

            // verify that the digest of first 32 bytes matches last 32 bytes
            for (i = 0; i < 32; i++) {
                if (digest[i] != decoded[i + 32]) {
                    // incorrectly decoded: we're not the intended recipient
//                    System.out.println("failed: " + i);
//                    System.out.println();
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

    private final static org.slf4j.Logger log = org.slf4j.LoggerFactory
            .getLogger(Crypto.class);
}
