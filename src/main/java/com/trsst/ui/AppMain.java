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
import java.util.List;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Logger;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.web.PopupFeatures;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.util.Callback;

import com.trsst.Command;
import com.trsst.Common;

/**
 * Creates a javafx frame just to embed a real live webkit browser. This exists
 * as a stopgap to decouple the development of the web UI from the development
 * of the all-javascript client.
 */
public class AppMain extends javafx.application.Application {

    private WebView webView;
    private static AppleEvents appleEvents;
    private static String serviceUrl;

    public static void main(String[] argv) {

        if (argv.length > 0) {
            serviceUrl = argv[0];
        }

        // register osx-native event handlers if needed
        try {
            // NOTE: must happen first to collect launch events
            appleEvents = new AppleEvents();
            // major improvement in font rendering on OSX
            System.setProperty("prism.lcdtext", "false");
        } catch (Throwable t) {
            // probably wrong platform: ignore
            log.warn("Could not load osx events: " + t.getMessage());
        }

        if (System.getProperty("com.trsst.server.relays") == null) {
            // if unspecified, default relay to home.trsst.com
            System.setProperty("com.trsst.server.relays",
                    "https://home.trsst.com/feed");
        }

        try {
            // write to log file if configured
            String path = System.getProperty("com.trsst.logfile");
            if (path != null) {
                Handler handler = new FileHandler(path, 1024 * 1024, 3);
                Logger.getLogger("").addHandler(handler);
                log.info("Writing log file to: " + path);
            }
        } catch (SecurityException e) {
            log.warn("No permission to write to log file", e);
        } catch (IOException e) {
            log.warn("Can't write to log file", e);
        }
        
        // we connect with our local server with a self-signed certificate:
        // we create our server with a random port that would fail to bind
        // if there were a mitm that happened to be serving on that port.
        Common.enableAnonymousSSL();

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

        String url = serviceUrl;
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
        // NOTE: must happen last to receive non-launch events
        try {
            appleEvents.run();
        } catch (Throwable t) {
            // probably wrong platform: ignore
            log.warn("Could not start osx events: " + t.getMessage());
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

    private final static org.slf4j.Logger log = org.slf4j.LoggerFactory
            .getLogger(Command.class);
}
