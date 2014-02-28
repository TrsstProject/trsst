package com.trsst.ui;

import com.apple.eawt.AboutHandler;
import com.apple.eawt.AppEvent.AboutEvent;
import com.apple.eawt.AppEvent.OpenFilesEvent;
import com.apple.eawt.AppEvent.OpenURIEvent;
import com.apple.eawt.AppEvent.PreferencesEvent;
import com.apple.eawt.AppEvent.PrintFilesEvent;
import com.apple.eawt.AppEvent.QuitEvent;
import com.apple.eawt.Application;
import com.apple.eawt.OpenFilesHandler;
import com.apple.eawt.OpenURIHandler;
import com.apple.eawt.PreferencesHandler;
import com.apple.eawt.PrintFilesHandler;
import com.apple.eawt.QuitHandler;
import com.apple.eawt.QuitResponse;

/**
 * Custom handlers for OS X launch services; allows us to be registered as an
 * system feed reader.
 * 
 * @author mpowers
 * 
 */
public class AppleEvents implements OpenURIHandler, OpenFilesHandler,
        PreferencesHandler, PrintFilesHandler, QuitHandler, AboutHandler,
        Runnable {

    public AppleEvents() {
        // NOTE: need to invoke application here to collect launch events
        Application.getApplication().setDockIconBadge("...");
    }

    public void run() {
        // NOTE: if these are invoked earlier, jfx disrupts them or something
        Application.getApplication().setDockIconBadge("");
        Application.getApplication().setOpenURIHandler(this);
        Application.getApplication().setOpenFileHandler(this);
        log.info("Now receiving OSX events");
    }

    private final static org.slf4j.Logger log = org.slf4j.LoggerFactory
            .getLogger(AppleEvents.class);

    @Override
    public void openURI(OpenURIEvent e) {
        // Application.getApplication().setDockIconBadge("URI");
        log.info("openURI: " + e.getURI());
        AppMain.getInstance().openURI(e.getURI());

    }

    @Override
    public void handleAbout(AboutEvent e) {
        log.info("handleAbout: ");
        // Application.getApplication().setDockIconBadge("About");
    }

    @Override
    public void handleQuitRequestWith(QuitEvent e, QuitResponse arg1) {
        log.info("handleQuitRequestWith: ");
        // Application.getApplication().setDockIconBadge("Quit");
    }

    @Override
    public void printFiles(PrintFilesEvent e) {
        log.info("printFiles: ");
        // Application.getApplication().setDockIconBadge("Print");
    }

    @Override
    public void handlePreferences(PreferencesEvent e) {
        log.info("handlePreferences: ");
        // Application.getApplication().setDockIconBadge("Prefs");
    }

    @Override
    public void openFiles(OpenFilesEvent e) {
        // Application.getApplication().setDockIconBadge("File");
        log.info("openFiles: " + e.getSearchTerm() + " : " + e.getFiles());
        AppMain.getInstance().openFiles(e.getFiles());
    }

}
