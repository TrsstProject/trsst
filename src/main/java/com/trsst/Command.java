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

import java.io.BufferedInputStream;
import java.io.Console;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SignatureException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.tika.Tika;
import org.eclipse.jetty.servlet.ServletHolder;

import com.trsst.client.Client;
import com.trsst.client.EntryOptions;
import com.trsst.client.FeedOptions;
import com.trsst.server.Server;
import com.trsst.server.TrsstAdapter;
import com.trsst.ui.AppMain;
import com.trsst.ui.AppServlet;

/**
 * Command-line program that implements the application-level features needed to
 * use the Trsst protocol: key management and user input and output.<br/>
 * 
 * Application-level features not implemented here include syncrhonization of
 * feed subscriptions and keystores between user clients, and generation and
 * distribution of confidential public keys to groups of other users to form the
 * equivalent of "circles" or "friend lists".
 * 
 * A trsst client must to connect to a host server. If no home server is
 * specified, this client will start a temporary server on the local machine,
 * and close it when finished.
 * 
 * A client instance stores user keystores in a directory called "trsstd" in the
 * current user's home directory, or the path indicated in the
 * "com.trsst.client.storage" system property.
 * 
 * There are three basic operations:<br/>
 * <ul>
 * 
 * <li>pull: pulls the specified feed from the specified host server.<br/>
 * 
 * <li>push: pushes the specified feed from the current host server to the
 * specified remote server.<br/>
 * 
 * <li>post: posts a new entry to the specified feed on the host server,
 * creating a new feed if no feed id is specified.
 * </ul>
 * 
 * This program can alternately start a standalone server instance:
 * <ul>
 * 
 * <li>port: starts a trsst server on the specified port on this machine.
 * </ul>
 * 
 * A server instance defaults to local file persistence in a directory called
 * "trsstd" in the current user's home directory, or the path indicated in the
 * "com.trsst.server.storage" system property.
 * 
 * Application-level features not implemented here include syncrhonization of
 * feed subscriptions and keystores between user clients, and generation and
 * distribution of confidential public keys to groups of other users to form the
 * equivalent of "circles" or "friend lists".
 * 
 * 
 * @author mpowers
 */
@SuppressWarnings("deprecation")
public class Command {

    static {
        if (System.getProperty("org.slf4j.simpleLogger.defaultLogLevel") == null) {
            // if unspecified, default to error-level logging for jetty
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        }
    }

    public static void main(String[] argv) {

        // during alpha period: expire after one week
        Date builtOn = Common.getBuildDate();
        if (builtOn != null) {
            long weekMillis = 1000 * 60 * 60 * 24 * 7;
            Date expiry = new Date(builtOn.getTime() + weekMillis);
            if (new Date().after(expiry)) {
                System.err.println("Build expired on: " + expiry);
                System.err
                        .println("Please obtain a more recent build for testing.");
                System.exit(1);
            } else {
                System.err.println("Build will expire on: " + expiry);
            }
        }

        // if unspecified, default relay to home.trsst.com
        if (System.getProperty("com.trsst.server.relays") == null) {
            System.setProperty("com.trsst.server.relays",
                    "http://home.trsst.com/feed");
        }

        // default to user-friendlier file names
        String home = System.getProperty("user.home", ".");
        if (System.getProperty("com.trsst.client.storage") == null) {
            File client = new File(home, "Trsst Keyfiles");
            System.setProperty("com.trsst.client.storage",
                    client.getAbsolutePath());
        }
        if (System.getProperty("com.trsst.server.storage") == null) {
            File server = new File(home, "Trsst System Cache");
            System.setProperty("com.trsst.server.storage",
                    server.getAbsolutePath());
        }
        // TODO: try to detect if launching from external volume like a flash
        // drive and store on the local flash drive instead

        Console console = System.console();
        int result;
        try {
            if (console == null && argv.length == 0) {
                argv = new String[] { "port", "--gui" };
            }
            result = new Command().doBegin(argv, System.out, System.in);

            // task queue prevents exit unless stopped
            if (TrsstAdapter.TASK_QUEUE != null) {
                TrsstAdapter.TASK_QUEUE.cancel();
            }
        } catch (Throwable t) {
            result = 1; // "general catchall error code"
            log.error("Unexpected error, exiting.", t);
        }

        // if error
        if (result != 0) {
            // force exit
            System.exit(result);
        }
    }

    private Options portOptions;
    private Options pullOptions;
    private Options mergedOptions;
    private Options postOptions;
    private Option helpOption;
    private boolean format = false;

    @SuppressWarnings("static-access")
    private void buildOptions(String[] argv, PrintStream out, InputStream in) {

        // NOTE: OptionsBuilder is NOT thread-safe
        // which was causing us random failures.
        Option o;

        portOptions = new Options();

        o = new Option(null, "Expose REST API");
        o.setRequired(false);
        o.setArgs(0);
        o.setLongOpt("api");
        portOptions.addOption(o);

        o = new Option(null, "Launch embedded GUI");
        o.setRequired(false);
        o.setArgs(0);
        o.setLongOpt("gui");
        portOptions.addOption(o);

        o = new Option(null, "Turn off SSL");
        o.setRequired(false);
        o.setArgs(0);
        o.setLongOpt("clear");
        portOptions.addOption(o);

        pullOptions = new Options();

        o = new Option("h", "Set host server for this operation");
        o.setRequired(false);
        o.setArgs(1);
        o.setArgName("url");
        o.setLongOpt("host");
        pullOptions.addOption(o);

        o = new Option("d", "Decrypt entries as specified recipient id");
        o.setRequired(false);
        o.setArgs(1);
        o.setArgName("id");
        o.setLongOpt("decrypt");
        pullOptions.addOption(o);

        postOptions = new Options();

        o = new Option("a", "Attach the specified file, or - for std input");
        o.setRequired(false);
        o.setOptionalArg(true);
        o.setArgName("file");
        o.setLongOpt("attach");
        postOptions.addOption(o);

        o = new Option("b", "Set base URL for this feed");
        o.setRequired(false);
        o.setArgs(1);
        o.setArgName("url");
        o.setLongOpt("base");
        postOptions.addOption(o);

        o = new Option("p", "Specify passphrase on the command line");
        o.setRequired(false);
        o.setArgs(1);
        o.setArgName("text");
        o.setLongOpt("pass");
        postOptions.addOption(o);

        o = new Option("s", "Specify status update on command line");
        o.setRequired(false);
        o.setArgs(1);
        o.setArgName("text");
        o.setLongOpt("status");
        postOptions.addOption(o);

        o = new Option("u", "Attach the specified url to the new entry");
        o.setRequired(false);
        o.setArgs(1);
        o.setArgName("url");
        o.setLongOpt("url");
        postOptions.addOption(o);

        o = new Option("v", "Specify an activitystreams verb for this entry");
        o.setRequired(false);
        o.setArgs(1);
        o.setArgName("verb");
        o.setLongOpt("verb");
        postOptions.addOption(o);

        o = new Option("r", "Add a mention");
        o.setRequired(false);
        o.setArgs(1);
        o.setArgName("id");
        o.setLongOpt("mention");
        postOptions.addOption(o);

        o = new Option("g", "Add a tag");
        o.setRequired(false);
        o.setArgs(1);
        o.setArgName("text");
        o.setLongOpt("tag");
        postOptions.addOption(o);

        o = new Option("c", "Specify entry content on command line");
        o.setRequired(false);
        o.setArgs(1);
        o.setArgName("text");
        o.setLongOpt("content");
        postOptions.addOption(o);

        o = new Option("t", "Set this feed's title");
        o.setRequired(false);
        o.setArgs(1);
        o.setArgName("text");
        o.setLongOpt("title");
        postOptions.addOption(o);

        o = new Option(null, "Set this feed's subtitle");
        o.setRequired(false);
        o.setArgs(1);
        o.setArgName("text");
        o.setLongOpt("subtitle");
        postOptions.addOption(o);

        o = new Option("n", "Set this feed's author name");
        o.setRequired(false);
        o.setArgs(1);
        o.setArgName("text");
        o.setLongOpt("name");
        postOptions.addOption(o);

        o = new Option("e", "Encrypt entry for specified public key");
        o.setRequired(false);
        o.setArgs(1);
        o.setArgName("pubkey");
        o.setLongOpt("encrypt");
        postOptions.addOption(o);

        o = new Option("m", "Set this feed's author email");
        o.setRequired(false);
        o.setArgs(1);
        o.setArgName("email");
        o.setLongOpt("mail");
        postOptions.addOption(o);

        o = new Option("i", "Set as this feed's icon or specify url");
        o.setRequired(false);
        o.setOptionalArg(true);
        o.setArgName("url");
        o.setLongOpt("icon");
        postOptions.addOption(o);

        o = new Option("l", "Set as this feed's logo or specify url");
        o.setRequired(false);
        o.setOptionalArg(true);
        o.setArgName("url");
        o.setLongOpt("logo");
        postOptions.addOption(o);

        o = new Option(null, "Generate feed id with specified prefix");
        o.setRequired(false);
        o.setArgs(1);
        o.setArgName("prefix");
        o.setLongOpt("vanity");
        postOptions.addOption(o);

        // merge options parameters
        mergedOptions = new Options();
        for (Object obj : pullOptions.getOptions()) {
            mergedOptions.addOption((Option) obj);
        }
        for (Object obj : postOptions.getOptions()) {
            mergedOptions.addOption((Option) obj);
        }
        for (Object obj : portOptions.getOptions()) {
            mergedOptions.addOption((Option) obj);
        }
        helpOption = OptionBuilder.isRequired(false).withLongOpt("help")
                .withDescription("Display these options").create('?');
        mergedOptions.addOption(helpOption);
    }

    public int doBegin(String[] argv, PrintStream out, InputStream in) {

        buildOptions(argv, out, in);

        int result = 0;
        Server server = null;
        try {

            CommandLineParser argParser = new GnuParser();
            CommandLine commands;
            try {
                commands = argParser.parse(mergedOptions, argv);
            } catch (Throwable t) {
                log.error(
                        "Unexpected error parsing arguments: "
                                + Arrays.asList(argv), t);
                return 127;
            }
            LinkedList<String> arguments = new LinkedList<String>();
            for (Object o : commands.getArgList()) {
                arguments.add(o.toString()); // dodge untyped param warning
            }
            if (commands.hasOption("?")) {
                printAllUsage();
                return 0;
            }
            if (arguments.size() < 1) {
                printAllUsage();
                return 127; // "command not found"
            }

            String mode = arguments.removeFirst().toString();

            // for port requests
            if ("port".equals(mode)) {
                // start a server and exit
                result = doPort(commands, arguments);
                return 0;
            }

            // attempt to parse next argument as a server url
            Client client = null;
            if (commands.hasOption("h")) {
                String host = commands.getOptionValue("h");
                try {
                    URL url = new URL(host);
                    // this argument is a server url
                    client = new Client(url);
                    System.err.println("Using service: " + host);
                } catch (MalformedURLException e) {
                    // otherwise: ignore and continue
                    System.err.println("Bad hostname: " + host);
                }
            }

            // if a server url wasn't specified
            if (client == null) {
                // start a client with a local server
                server = new Server();
                client = new Client(server.getServiceURL());
                System.err.println("Starting temporary service at: "
                        + server.getServiceURL());
            }

            if ("pull".equals(mode)) {
                // pull feeds from server
                result = doPull(client, commands, arguments, out);
            } else if ("push".equals(mode)) {
                // push feeds to server
                result = doPush(client, commands, arguments, out);
            } else if ("post".equals(mode)) {
                // post (and push) entries
                result = doPost(client, commands, arguments, out, in);
            } else {
                printAllUsage();
                result = 127; // "command not found"
            }
        } catch (Throwable t) {
            log.error("Unexpected error: " + t, t);
            result = 1; // "catchall for general errors"
        }
        if (server != null) {
            server.stop();
        }
        return result;
    }

    public int doPull(Client client, CommandLine commands,
            LinkedList<String> arguments, PrintStream out) {

        if (arguments.size() < 1) {
            printPullUsage();
            return 127; // "command not found"
        }

        // decryption option
        String id = commands.getOptionValue("d");
        PrivateKey[] decryptionKeys = null;

        if (id != null) {
            // obtain password
            id = Common.toFeedIdString(id);
            char[] password = null;
            String pass = commands.getOptionValue("p");
            if (pass != null) {
                password = pass.toCharArray();
            } else {
                try {
                    Console console = System.console();
                    if (console != null) {
                        password = console.readPassword("Password: ");
                    } else {
                        log.info("No console detected for password input.");
                    }
                } catch (Throwable t) {
                    log.error("Unexpected error while reading password", t);
                }
            }
            if (password == null) {
                log.error("Password is required to decrypt.");
                return 127; // "command not found"
            }
            if (password.length < 6) {
                System.err
                        .println("Password must be at least six characters in length.");
                return 127; // "command not found"
            }

            // obtain keys
            KeyPair signingKeys = null;
            KeyPair encryptionKeys = null;
            String keyPath = commands.getOptionValue("k");

            File keyFile;
            if (keyPath != null) {
                keyFile = new File(keyPath, id + Common.KEY_EXTENSION);
            } else {
                keyFile = new File(Common.getClientRoot(), id
                        + Common.KEY_EXTENSION);
            }

            if (keyFile.exists()) {
                System.err.println("Using existing account id: " + id);

            } else {
                System.err.println("Cannot locate keys for account id: " + id);
                return 78; // "configuration error"
            }

            signingKeys = readSigningKeyPair(id, keyFile, password);
            if (signingKeys != null) {
                encryptionKeys = readEncryptionKeyPair(id, keyFile, password);
                if (encryptionKeys == null) {
                    decryptionKeys = new PrivateKey[] { signingKeys
                            .getPrivate() };
                } else {
                    decryptionKeys = new PrivateKey[] {
                            encryptionKeys.getPrivate(),
                            signingKeys.getPrivate() };
                }
            }
        }

        List<String> ids = new LinkedList<String>();
        for (String arg : arguments) {
            ids.add(arg);
        }

        for (String feedId : ids) {
            try {
                Object feed;
                if (decryptionKeys != null) {
                    feed = client.pull(feedId, decryptionKeys);
                } else {
                    feed = client.pull(feedId);
                }
                if (feed != null) {
                    if (format) {
                        out.println(Common.formatXML(feed.toString()));
                    } else {
                        out.println(feed.toString());
                    }
                } else {
                    System.err.println("Could not fetch: " + feedId + " : "
                            + client);
                }
            } catch (Throwable t) {
                log.error("Unexpected error on pull: " + feedId + " : "
                        + client, t);
            }
        }
        return 0; // "OK"
    }

    public int doPush(Client client, CommandLine commands,
            LinkedList<String> arguments, PrintStream out) {

        // if a second argument was specified
        if (arguments.size() < 2) {
            printPushUsage();
            return 127; // "command not found"
        }
        URL url;
        String host = arguments.removeFirst().toString();
        Client destinationClient;
        try {
            url = new URL(host);
            // this argument is a server url
            destinationClient = new Client(url);
            System.err.println("Using service: " + host);
        } catch (MalformedURLException e) {
            printPushUsage();
            return 127; // "command not found"
        }

        for (String id : arguments) {
            System.out.println(destinationClient.push(client.pull(id), url));
            // Feed feed = client.pull(id);
            // if ( feed != null ) {
            // feed = client.push(feed, url);
            // if ( feed != null ) {
            // out.println(feed);
            // } else {
            // System.err.println("Failed to push feed for id: " + id);
            // }
            // } else {
            // System.err.println("Failed to pull feed for id: " + id);
            // }
        }
        return 0; // "OK"
    }

    public int doPort(CommandLine commands, LinkedList<String> arguments) {

        boolean apiOption = commands.hasOption("api");
        boolean guiOption = commands.hasOption("gui");
        boolean clearOption = commands.hasOption("clear");
        int portOption = 0; // default to random port

        if (arguments.size() > 0) {
            String portString = arguments.removeFirst().toString();
            try {
                portOption = Integer.parseInt(portString);
            } catch (NumberFormatException t) {
                log.error("Invalid port: " + portString);
                return 78; // "configuration error"
            }
        }

        Server service;
        try {
            service = new Server(portOption, "feed", !clearOption);
            if (apiOption || guiOption) {
                service.getServletContextHandler().addServlet(
                        new ServletHolder(new AppServlet(!apiOption)), "/*");
            }
            System.err.println("Services now available at: "
                    + service.getServiceURL());
        } catch (Exception e) {
            log.error("Could not start server: " + e);
            return 71; // "system error"
        }

        // attempt GUI mode
        if (guiOption) {
            try {
                AppMain.main(new String[] { service.getServiceURL().toString() });
            } catch (Throwable t) {
                log.error("Could not launch UI client", t);
            }
        }
        return 0; // "OK"
    }

    public int doPost(Client client, CommandLine commands,
            LinkedList<String> arguments, PrintStream out, InputStream in) {

        String id = null;

        if (arguments.size() == 0 && commands.getArgList().size() == 0) {
            printPostUsage();
            return 127; // "command not found"
        }

        if (arguments.size() > 0) {
            id = arguments.removeFirst();
            System.err.println("Obtaining keys for feed id: " + id);
        } else {
            System.err.println("Generating new feed id... ");
        }

        // read input text
        String subject = commands.getOptionValue("s");
        String verb = commands.getOptionValue("v");
        String base = commands.getOptionValue("b");
        String body = commands.getOptionValue("c");
        String name = commands.getOptionValue("n");
        String email = commands.getOptionValue("m");
        String title = commands.getOptionValue("t");
        String subtitle = commands.getOptionValue("subtitle");
        String icon = commands.getOptionValue("i");
        if (icon == null && commands.hasOption("i")) {
            icon = "-";
        }
        String logo = commands.getOptionValue("l");
        if (logo == null && commands.hasOption("l")) {
            logo = "-";
        }
        String attach = commands.getOptionValue("a");
        if (attach == null && commands.hasOption("a")) {
            attach = "-";
        }
        String[] recipients = commands.getOptionValues("e");
        String[] mentions = commands.getOptionValues("r");
        String[] tags = commands.getOptionValues("g");
        String url = commands.getOptionValue("u");
        String vanity = commands.getOptionValue("vanity");

        // obtain password
        char[] password = null;
        String pass = commands.getOptionValue("p");
        if (pass != null) {
            password = pass.toCharArray();
        } else {
            try {
                Console console = System.console();
                if (console != null) {
                    password = console.readPassword("Password: ");
                } else {
                    log.info("No console detected for password input.");
                }
            } catch (Throwable t) {
                log.error("Unexpected error while reading password", t);
            }
        }
        if (password == null) {
            log.error("Password is required to post.");
            return 127; // "command not found"
        }
        if (password.length < 6) {
            System.err
                    .println("Password must be at least six characters in length.");
            return 127; // "command not found"
        }

        // obtain keys
        KeyPair signingKeys = null;
        KeyPair encryptionKeys = null;
        String keyPath = commands.getOptionValue("k");

        // if id was not specified from the command line
        if (id == null) {

            // if password was not specified from command line
            if (pass == null) {
                try {
                    // verify password
                    char[] verify = null;
                    Console console = System.console();
                    if (console != null) {
                        verify = console.readPassword("Re-type Password: ");
                    } else {
                        log.info("No console detected for password verification.");
                    }
                    if (verify == null || verify.length != password.length) {
                        System.err.println("Passwords do not match.");
                        return 127; // "command not found"
                    }
                    for (int i = 0; i < verify.length; i++) {
                        if (verify[i] != password[i]) {
                            System.err.println("Passwords do not match.");
                            return 127; // "command not found"
                        }
                        verify[i] = 0;
                    }
                } catch (Throwable t) {
                    log.error(
                            "Unexpected error while verifying password: "
                                    + t.getMessage(), t);
                }
            }

            // create new account
            if (vanity != null) {
                System.err.println("Searching for vanity feed id prefix: "
                        + vanity);
                switch (vanity.length()) {
                case 0:
                case 1:
                    break;
                case 2:
                    System.err.println("This may take several minutes.");
                    break;
                case 3:
                    System.err.println("This may take several hours.");
                    break;
                case 4:
                    System.err.println("This may take several days.");
                    break;
                case 5:
                    System.err.println("This may take several months.");
                    break;
                default:
                    System.err.println("This may take several years.");
                    break;
                }
                System.err.println("Started: " + new Date());
                System.err.println("^C to exit");
                vanity = "1" + vanity;
            }
            do {
                signingKeys = Common.generateSigningKeyPair();
                id = Common.toFeedId(signingKeys.getPublic());
            } while (vanity != null && !id.startsWith(vanity));
            if (vanity != null) {
                System.err.println("Finished: " + new Date());
            }

            encryptionKeys = Common.generateEncryptionKeyPair();
            System.err.println("New feed id created: " + id);

            File keyFile;
            if (keyPath != null) {
                keyFile = new File(keyPath, id + Common.KEY_EXTENSION);
            } else {
                keyFile = new File(Common.getClientRoot(), id
                        + Common.KEY_EXTENSION);
            }

            // persist to keystore
            writeSigningKeyPair(signingKeys, id, keyFile, password);
            writeEncryptionKeyPair(encryptionKeys, id, keyFile, password);

        } else {

            File keyFile;
            if (keyPath != null) {
                keyFile = new File(Common.getClientRoot(), keyPath);
            } else {
                keyFile = new File(Common.getClientRoot(), id
                        + Common.KEY_EXTENSION);
            }

            if (keyFile.exists()) {
                System.err.println("Using existing account id: " + id);

            } else {
                System.err.println("Cannot locate keys for account id: " + id);
                return 78; // "configuration error"
            }

            signingKeys = readSigningKeyPair(id, keyFile, password);
            if (signingKeys != null) {
                encryptionKeys = readEncryptionKeyPair(id, keyFile, password);
                if (encryptionKeys == null) {
                    encryptionKeys = signingKeys;
                }
            }
        }

        // clear password chars
        for (int i = 0; i < password.length; i++) {
            password[i] = 0;
        }
        if (signingKeys == null) {
            System.err.println("Could not obtain keys for signing.");
            return 73; // "can't create output error"
        }

        String[] recipientIds = null;
        if (recipients != null) {
            LinkedList<String> keys = new LinkedList<String>();
            for (int i = 0; i < recipients.length; i++) {
                if ("-".equals(recipients[i])) {
                    // "-" is shorthand for encrypt for mentioned ids
                    if (mentions != null) {
                        for (String mention : mentions) {
                            if (Common.isFeedId(mention)) {
                                keys.add(mention);
                            }
                        }
                    }
                } else if (Common.isFeedId(recipients[i])) {
                    keys.add(recipients[i]);
                } else {
                    log.warn("Could not parse recipient id: " + recipients[i]);
                }
            }
            recipientIds = keys.toArray(new String[0]);
        }

        // handle binary attachment
        String mimetype = null;
        byte[] attachment = null;
        if (attach != null) {
            InputStream input = null;
            try {
                if ("-".equals(attach)) {
                    input = new BufferedInputStream(in);
                } else {
                    File file = new File(attach);
                    input = new BufferedInputStream(new FileInputStream(file));
                    System.err.println("Attaching: " + file.getCanonicalPath());
                }
                attachment = Common.readFully(input);
                mimetype = new Tika().detect(attachment);
                System.err.println("Detected type: " + mimetype);
            } catch (Throwable t) {
                log.error("Could not read attachment: " + attach, t);
                return 73; // "can't create output error"
            } finally {
                try {
                    input.close();
                } catch (IOException ioe) {
                    // suppress any futher error on closing
                }
            }
        }

        Object result;
        try {
            EntryOptions options = new EntryOptions();
            options.setStatus(subject);
            options.setVerb(verb);
            if (mentions != null) {
                options.setMentions(mentions);
            }
            if (tags != null) {
                options.setTags(tags);
            }
            options.setBody(body);
            if (attachment != null) {
                options.addContentData(attachment, mimetype);
            } else if (url != null) {
                options.setContentUrl(url);
            }
            FeedOptions feedOptions = new FeedOptions();
            feedOptions.setAuthorEmail(email);
            feedOptions.setAuthorName(name);
            feedOptions.setTitle(title);
            feedOptions.setSubtitle(subtitle);
            feedOptions.setBase(base);
            if (icon != null) {
                if ("-".equals(icon)) {
                    feedOptions.setAsIcon(true);
                } else {
                    feedOptions.setIconURL(icon);
                }
            }
            if (logo != null) {
                if ("-".equals(logo)) {
                    feedOptions.setAsLogo(true);
                } else {
                    feedOptions.setLogoURL(logo);
                }
            }
            if (recipientIds != null) {
                EntryOptions publicEntry = new EntryOptions().setStatus(
                        "Encrypted content").setVerb("encrypt");
                // TODO: add duplicate mentions to outside of envelope
                options.encryptFor(recipientIds, publicEntry);
            }
            result = client.post(signingKeys, encryptionKeys, options,
                    feedOptions);
        } catch (IllegalArgumentException e) {
            log.error("Invalid request: " + id + " : " + e.getMessage(), e);
            return 76; // "remote error"
        } catch (IOException e) {
            log.error("Error connecting to service for id: " + id, e);
            return 76; // "remote error"
        } catch (org.apache.abdera.security.SecurityException e) {
            log.error("Error generating signatures for id: " + id, e);
            return 73; // "can't create output error"
        } catch (Exception e) {
            log.error("General security error for id: " + id, e);
            return 74; // "general io error"
        }

        if (result != null) {
            if (format) {
                out.println(Common.formatXML(result.toString()));
            } else {
                out.println(result.toString());
            }
        }

        return 0; // "OK"
    }

    private void printAllUsage() {
        System.err.println(Common.getBuildString());
        printPostUsage();
        printPullUsage();
        printPushUsage();
        printPortUsage();
    }

    private void printPullUsage() {
        HelpFormatter formatter = new HelpFormatter();
        formatter.setSyntaxPrefix("");
        formatter.printHelp("pull <id>... ", pullOptions);
    }

    private void printPushUsage() {
        HelpFormatter formatter = new HelpFormatter();
        formatter.setSyntaxPrefix("");
        formatter.printHelp("push <url> <id>...", pullOptions);
    }

    private void printPortUsage() {
        HelpFormatter formatter = new HelpFormatter();
        formatter.setSyntaxPrefix("");
        formatter.printHelp("port [<portnumber>]", portOptions);
    }

    private void printPostUsage() {
        HelpFormatter formatter = new HelpFormatter();
        formatter.setSyntaxPrefix("");
        formatter.printHelp(
                "post [<id>] [--status <text>] [--encrypt <pubkey>]",
                postOptions);
    }

    public static final KeyPair readSigningKeyPair(String id, File file,
            char[] pwd) {
        return readKeyPairFromFile(id + '-' + Common.SIGN, file, pwd);
    }

    public static final KeyPair readEncryptionKeyPair(String id, File file,
            char[] pwd) {
        return readKeyPairFromFile(id + '-' + Common.ENCRYPT, file, pwd);
    }

    public static final void writeSigningKeyPair(KeyPair keyPair, String id,
            File file, char[] pwd) {
        writeKeyPairToFile(keyPair,
                createCertificate(keyPair, "SHA1withECDSA"), id + '-'
                        + Common.SIGN, file, pwd);
    }

    public static final void writeEncryptionKeyPair(KeyPair keyPair, String id,
            File file, char[] pwd) {
        writeKeyPairToFile(keyPair,
                createCertificate(keyPair, "SHA1withECDSA"), id + '-'
                        + Common.ENCRYPT, file, pwd);
    }

    public static final KeyPair readKeyPairFromFile(String alias, File file,
            char[] pwd) {
        FileInputStream input = null;
        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            input = new FileInputStream(file);
            keyStore.load(new FileInputStream(file), pwd);
            input.close();

            KeyStore.PrivateKeyEntry pkEntry = (KeyStore.PrivateKeyEntry) keyStore
                    .getEntry(alias, new KeyStore.PasswordProtection(pwd));
            PrivateKey privateKey = pkEntry.getPrivateKey();
            PublicKey publicKey = pkEntry.getCertificate().getPublicKey();
            return new KeyPair(publicKey, privateKey);
        } catch (/* javax.crypto.BadPaddingException */IOException bpe) {
            log.error("Passphrase could not decrypt key: " + bpe.getMessage());
        } catch (Throwable e) {
            log.error("Unexpected error while reading key: " + e.getMessage(),
                    e);
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException e) {
                    // ignore while closing
                    log.trace("Error while closing: " + e.getMessage(), e);
                }
            }
        }
        return null;
    }

    public static final void writeKeyPairToFile(KeyPair keyPair,
            X509Certificate cert, String alias, File file, char[] pwd) {
        FileInputStream input = null;
        FileOutputStream output = null;
        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            if (file.exists()) {
                input = new FileInputStream(file);
                keyStore.load(new FileInputStream(file), pwd);
                input.close();
            } else {
                keyStore.load(null); // weird but required
            }

            // save my private key
            keyStore.setKeyEntry(alias, keyPair.getPrivate(), pwd,
                    new X509Certificate[] { cert });

            // store away the keystore
            output = new java.io.FileOutputStream(file);
            keyStore.store(output, pwd);
            output.flush();
        } catch (Exception e) {
            log.error("Error while storing key: " + e.getMessage(), e);
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException e) {
                    // ignore while closing
                    log.trace("Error while closing: " + e.getMessage(), e);
                }
            }
            if (output != null) {
                try {
                    output.close();
                } catch (IOException e) {
                    // ignore while closing
                    log.trace("Error while closing: " + e.getMessage(), e);
                }
            }
        }
    }

    private static final X509Certificate createCertificate(KeyPair keyPair,
            String algorithm) {
        org.bouncycastle.x509.X509V3CertificateGenerator certGen = new org.bouncycastle.x509.X509V3CertificateGenerator();

        long now = System.currentTimeMillis();
        certGen.setSerialNumber(java.math.BigInteger.valueOf(now));

        org.bouncycastle.jce.X509Principal subject = new org.bouncycastle.jce.X509Principal(
                "CN=Trsst Keystore,DC=trsst,DC=com");
        certGen.setIssuerDN(subject);
        certGen.setSubjectDN(subject);

        Date fromDate = new java.util.Date(now);
        certGen.setNotBefore(fromDate);
        Calendar cal = new java.util.GregorianCalendar();
        cal.setTime(fromDate);
        cal.add(java.util.Calendar.YEAR, 100);
        Date toDate = cal.getTime();
        certGen.setNotAfter(toDate);

        certGen.setPublicKey(keyPair.getPublic());
        certGen.setSignatureAlgorithm(algorithm);
        certGen.addExtension(
                org.bouncycastle.asn1.x509.X509Extensions.BasicConstraints,
                true, new org.bouncycastle.asn1.x509.BasicConstraints(false));
        certGen.addExtension(
                org.bouncycastle.asn1.x509.X509Extensions.KeyUsage,
                true,
                new org.bouncycastle.asn1.x509.KeyUsage(
                        org.bouncycastle.asn1.x509.KeyUsage.digitalSignature
                                | org.bouncycastle.asn1.x509.KeyUsage.keyEncipherment
                                | org.bouncycastle.asn1.x509.KeyUsage.keyCertSign
                                | org.bouncycastle.asn1.x509.KeyUsage.cRLSign));
        X509Certificate x509 = null;
        try {
            x509 = certGen.generateX509Certificate(keyPair.getPrivate());
        } catch (InvalidKeyException e) {
            log.error("Error generating certificate: invalid key", e);
        } catch (SecurityException e) {
            log.error("Unexpected error generating certificate", e);
        } catch (SignatureException e) {
            log.error("Error generating generating certificate signature", e);
        }
        return x509;
    }

    private final static org.slf4j.Logger log = org.slf4j.LoggerFactory
            .getLogger(Command.class);
}
