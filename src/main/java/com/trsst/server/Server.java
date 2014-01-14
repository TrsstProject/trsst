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
package com.trsst.server;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.util.Date;

import org.apache.abdera.protocol.server.servlet.AbderaServlet;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.ssl.SslContextFactory;

/**
 * Jetty-specific configuration to host an Abdera servlet that is configured to
 * serve the Trsst protocol.
 * 
 * @author mpowers
 */
public class Server {
    private final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(this
            .getClass());

    int port;
    String path;
    boolean secure;
    org.eclipse.jetty.server.Server server;

    public Server() throws Exception {
        this(0, null);
    }

    public Server(int port) throws Exception {
        this(port, null);
    }

    public Server(int port, String path) throws Exception {
        this(port, path, false);
    }

    public Server(int port, String path, boolean secure) throws Exception {
        try {
            this.secure = secure;
            if (path != null) {
                if (path.endsWith("/")) {
                    path = path.substring(0, path.length() - 1);
                }
                if (!path.startsWith("/")) {
                    path = "/" + path;
                }
            } else {
                path = "";
            }
            if (port == 0) {
                port = allocatePort();
            }
            server = new org.eclipse.jetty.server.Server(port);

            ServletContextHandler context = new ServletContextHandler(
                    ServletContextHandler.SESSIONS);
            context.setContextPath("/");
            server.setHandler(context);

            ServletHolder servletHolder = new ServletHolder(new AbderaServlet());
            servletHolder.setInitParameter(
                    "org.apache.abdera.protocol.server.Provider",
                    "com.trsst.server.AbderaProvider");
            context.addServlet(servletHolder, path + "/*");
            this.port = port;
            this.path = path;

            HttpConfiguration http_config = new HttpConfiguration();
            ServerConnector http = new ServerConnector(server,
                    new HttpConnectionFactory(http_config));
            http.setPort(port);
            http.setIdleTimeout(500000);

            if (secure) {

                http_config.setSecureScheme("https");
                http_config.setSecurePort(8443);
                http_config.setOutputBufferSize(32768);

                final KeyStore keystore = getKeyStore();
                SslContextFactory sslContextFactory = new SslContextFactory(
                        true);
                sslContextFactory.setKeyStore(keystore);
                sslContextFactory.setCertAlias("jetty");
                sslContextFactory.setKeyStorePassword("ignored");
                sslContextFactory.setKeyManagerPassword("ignored");
                sslContextFactory.setTrustAll(true);
                HttpConfiguration https_config = new HttpConfiguration(
                        http_config);
                https_config.addCustomizer(new SecureRequestCustomizer());

                ServerConnector https = new ServerConnector(
                        server,
                        new SslConnectionFactory(sslContextFactory, "http/1.1"),
                        new HttpConnectionFactory(https_config));
                https.setPort(port);
                https.setIdleTimeout(500000);
                server.setConnectors(new Connector[] { https });

            } else {
                server.setConnectors(new Connector[] { http });
            }

            server.start();

        } catch (Exception ioe) {
            log.error("could not start server on " + port + " : " + path, ioe);
            throw ioe;
        }
    }

    /**
     * Generates a new keystore containing a self-signed certificate. Would
     * prefer anon SSL ciphers, but this works albeit with scary warnings.
     * 
     * @return a keystore to secure SSL connections.
     */
    private KeyStore getKeyStore() {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator
                    .getInstance("RSA");
            keyPairGenerator.initialize(1024);
            KeyPair kp = keyPairGenerator.generateKeyPair();
            X509v3CertificateBuilder v3CertGen = new X509v3CertificateBuilder(
                    new X500Name("CN=0.0.0.0, OU=None, O=None, L=None, C=None"),
                    BigInteger.valueOf(new SecureRandom().nextInt()), new Date(
                            System.currentTimeMillis() - 1000L * 60 * 60 * 24
                                    * 30), new Date(System.currentTimeMillis()
                            + (1000L * 60 * 60 * 24 * 365 * 10)), new X500Name(
                            "CN=0.0.0.0, OU=None, O=None, L=None, C=None"),
                    SubjectPublicKeyInfo.getInstance(kp.getPublic()
                            .getEncoded()));
            ContentSigner signer = new JcaContentSignerBuilder(
                    "SHA256WithRSAEncryption").build(kp.getPrivate());
            Certificate certificate = new JcaX509CertificateConverter()
                    .getCertificate(v3CertGen.build(signer));

            final KeyStore keystore = KeyStore.getInstance(KeyStore
                    .getDefaultType());
            keystore.load(null); // bogus: required to "initialize" keystore
            keystore.setEntry("jetty",
                    new KeyStore.PrivateKeyEntry(kp.getPrivate(),
                            new Certificate[] { certificate }),
                    new KeyStore.PasswordProtection("ignored".toCharArray()));

            return keystore;
        } catch (NoSuchAlgorithmException e) {
            log.error(
                    "Could not generate self-signed certificate: missing provider",
                    e);
        } catch (OperatorCreationException e) {
            log.error("Could not generate self-signed certificate", e);
        } catch (CertificateException e) {
            log.error("Could not convert certificate to JCE", e);
        } catch (KeyStoreException e) {
            log.error("Could not generate keystore", e);
        } catch (IOException e) {
            log.error("Could not initialize keystore", e);
        }
        return null;
    }

    /**
     * Grabs a new server port. Borrowed from axiom.
     */
    private int allocatePort() {
        try {
            ServerSocket ss = new ServerSocket(0);
            int port = ss.getLocalPort();
            ss.close();
            return port;
        } catch (IOException ex) {
            log.error("Unable to allocate TCP port; defaulting to 54445.", ex);
            return 54445; // arbitrary port
        }
    }

    public URL getServiceURL() {
        URL result = null;
        String protocol = secure ? "https" : "http";
        try {
            result = new URL(protocol, "localhost", port, path); // default
            result = new URL(protocol, InetAddress.getLocalHost()
                    .getHostAddress(), port, path);
        } catch (MalformedURLException e) {
            // accept default
        } catch (UnknownHostException e) {
            // accept default
        }
        return result;
    }

    public void stop() {
        try {
            server.stop();
        } catch (Exception e) {
            log.error("Error while stopping server", e);
        }
        server.destroy();
    }
}