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

import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;

import javax.crypto.Cipher;

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

    public static byte[] encryptIES(byte[] before, PublicKey publicKey)
            throws SecurityException {
        byte[] after = null;
        try {
            IESCipher cipher = new IESCipher(new IESEngine(
                    new ECDHBasicAgreement(), new KDF2BytesGenerator(
                            new SHA1Digest()), new HMac(new SHA256Digest()),
                    new PaddedBufferedBlockCipher(new AESEngine())));

            // BC appears to be happier with BCECPublicKeys:
            // see BC's IESCipher.engineInit's check for ECPublicKey
            publicKey = new BCECPublicKey((ECPublicKey) publicKey, null);

            cipher.engineInit(Cipher.ENCRYPT_MODE, publicKey,
                    new SecureRandom());
            after = cipher.engineDoFinal(before, 0, before.length);
        } catch (Exception e) {
            log.error("Error while encrypting element", e);
            throw new SecurityException(e);
        }
        return after;
    }

    public static byte[] decryptIES(byte[] data, PrivateKey privateKey)
            throws SecurityException {
        try {
            IESCipher cipher = new IESCipher(new IESEngine(
                    new ECDHBasicAgreement(), new KDF2BytesGenerator(
                            new SHA1Digest()), new HMac(new SHA256Digest()),
                    new PaddedBufferedBlockCipher(new AESEngine())));

            // BC appears to be happier with BCECPublicKeys:
            // see BC's IESCipher.engineInit's check for ECPublicKey
            privateKey = new BCECPrivateKey((ECPrivateKey) privateKey, null);

            cipher.engineInit(Cipher.DECRYPT_MODE, privateKey,
                    new SecureRandom());
            return cipher.engineDoFinal(data, 0, data.length);
        } catch (Exception e) {
            log.trace("Error while decrypting: ");
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
