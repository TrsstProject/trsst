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
package com.trsst.ui;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Logger;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import com.trsst.Command;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.web.PopupFeatures;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.util.Callback;

/**
 * Creates a javafx frame just to embed a real live webkit browser. This exists
 * as a stopgap to decouple the development of the web UI from the development
 * of the all-javascript client.
 */
public class AppMain extends javafx.application.Application {

    private WebView webView;
    private static AppleEvents appleEvents;

    public static void main(String[] argv) {
        // registers osx-native event handlers
        //NOTE: must happen first to collect launch events
        try {
            appleEvents = new AppleEvents();
        } catch ( Throwable t ) {
            // probably wrong platform: ignore
            log.warn("Could not load osx events: " + t.getMessage() );
        }

        // try to set user-friendly client and server directories
        String home = System.getProperty("user.home", ".");
        File client = new File(home, "Trsst Keyfiles");
        System.setProperty("com.trsst.client.storage", client.getAbsolutePath());
        File server = new File(home, "Trsst System Cache");
        System.setProperty("com.trsst.server.storage", server.getAbsolutePath());
        // TODO: try to detect if launching from external volume like a flash
        // drive and store on the local flash drive instead

        if (System.getProperty("com.trsst.server.relays") == null) {
            // if unspecified, default relay to home.trsst.com
            System.setProperty("com.trsst.server.relays",
                  "http://home.trsst.com/feed");
        }

        try {
            // try to write to a log file in temporary directory
            String path = System.getProperty("java.io.tmpdir", "")
                    + "trsst.log";
            path = "/tmp/trsst.log";
            Handler handler = new FileHandler(path, 1024 * 1024, 3);
            Logger.getLogger("").addHandler(handler);
            log.info("Writing log file to: " + path);

        } catch (SecurityException e) {
            log.warn("No permission to write to log file", e);
        } catch (IOException e) {
            log.warn("Can't write to log file", e);
        }

        // major improvement in font rendering on OSX
        System.setProperty("prism.lcdtext", "false");

        // launch the app
        launch(argv);
    }

    public static AppMain instance;

    public static AppMain getInstance() {
        return instance;
    }

    @Override
    public void start(Stage stage) {

        instance = this;

        // we connect with our local server with a self-signed certificate:
        // we create our server with a random port that would fail to bind
        // if there were a mitm that happened to be serving on that port.
        enableAnonymousSSL();

        AppClient appClient = new AppClient();

        stage.setTitle("trsst");
        webView = new WebView();

        // intercept target=_blank hyperlinks
        webView.getEngine().setCreatePopupHandler(
                new Callback<PopupFeatures, WebEngine>() {
                    public WebEngine call(PopupFeatures config) {
                        // grab the last hyperlink that has :hover pseudoclass
                        Object o = webView
                                .getEngine()
                                .executeScript(
                                        "var list = document.querySelectorAll( ':hover' );"
                                                + "for (i=list.length-1; i>-1; i--) "
                                                + "{ if ( list.item(i).getAttribute('href') ) "
                                                + "{ list.item(i).getAttribute('href'); break; } }");

                        // open in native browser
                        try {
                            if (o != null) {
                                Desktop.getDesktop().browse(
                                        new URI(o.toString()));
                            } else {
                                log.error("No result from uri detector: " + o);
                            }
                        } catch (IOException e) {
                            log.error("Unexpected error obtaining uri: " + o, e);
                        } catch (URISyntaxException e) {
                            log.error("Could not interpret uri: " + o, e);
                        }

                        // prevent from opening in webView
                        return null;
                    }
                });

        String url = appClient.getServer().getServiceURL().toString();
        int i = url.lastIndexOf("/");
        if (i != -1) {
            url = url.substring(0, i);
        }
        webView.getEngine().load(url);
        StackPane root = new StackPane();
        root.getChildren().add(webView);
        Scene scene = new Scene(root, 900, 900);
        stage.setScene(scene);
        stage.show();

        // registers osx-native event handlers
        //NOTE: must happen last to receive non-launch events
        try {
            appleEvents.run();
        } catch ( Throwable t ) {
            // probably wrong platform: ignore
            log.warn("Could not start osx events: " + t.getMessage() );
        }
    }

    public void openURI(URI uri) {
        log.info("openURI: got this far 1: " + uri);
        // mac feed urls use a feed:// protocol; convert to http
        String url = uri.toString().replace("feed://", "http://");
        final String script = "controller.pushState('/" + url + "');";
        log.info("openURI: got this far 2: " + uri);
        Platform.runLater(new Runnable() {
            public void run() {
                try {
                    webView.getEngine().executeScript(script);
                } catch (Throwable t) {
                    log.error("Unexpected error: ", t);
                }
                log.info("openURI: got this far 3: " + script);
            }
        });
    }

    public void openFiles(final List<File> files) {
        Platform.runLater(new Runnable() {
            public void run() {
                // for (File s : files) {
                // TODO: read file for path
                // TODO: extract link rel=self
                // TODO: append that link to browser location
                // }
            }
        });
    }

    @Override
    public void stop() {
        System.exit(0);
    }

    public static void enableAnonymousSSL() {
        /*
         * fix for Exception in thread "main"
         * javax.net.ssl.SSLHandshakeException:
         * sun.security.validator.ValidatorException: PKIX path building failed:
         * sun.security.provider.certpath.SunCertPathBuilderException: unable to
         * find valid certification path to requested target
         */
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
    }

    private final static org.slf4j.Logger log = org.slf4j.LoggerFactory
            .getLogger(Command.class);
}
