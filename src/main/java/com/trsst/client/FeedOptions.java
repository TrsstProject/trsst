package com.trsst.client;

/**
 * Builder class for updating a feed and/or creating an entry.
 * 
 * @author mpowers
 */
public class FeedOptions {

    String name;
    String email;
    String title;
    String subtitle;
    String icon;
    String logo;

    /**
     * Create empty default post options. By default, no entry is created, and
     * no feed settings are updated, although a new feed may be created if it
     * does not already exist.
     */
    public FeedOptions() {
    }

    /** 
     * Convenience to reset these options to original state for reuse.
     */
    public void reset() {
        name = null;
        email = null;
        title = null;
        subtitle = null;
        icon = null;
        logo = null;
    }

    /**
     * @return the name
     */
    public String getAuthorName() {
        return name;
    }

    /**
     * @param name
     *            Updates the author name associated with the feed.
     */
    public FeedOptions setAuthorName(String name) {
        this.name = name;
        return this;
    }

    /**
     * @return the email
     */
    public String getAuthorEmail() {
        return email;
    }

    /**
     * @param email
     *            Updates the author email associated with the feed.
     */
    public FeedOptions setAuthorEmail(String email) {
        this.email = email;
        return this;
    }

    /**
     * @return the title
     */
    public String getFeedTitle() {
        return title;
    }

    /**
     * @param title
     *            Updates the title of the feed, or empty string to remove.
     */
    public FeedOptions setTitle(String title) {
        this.title = title;
        return this;
    }

    /**
     * @return the subtitle
     */
    public String getFeedSubtitle() {
        return subtitle;
    }

    /**
     * @param subtitle
     *            Updates the subtitle of the feed, or empty string to remove.
     */
    public FeedOptions setSubtitle(String subtitle) {
        this.subtitle = subtitle;
        return this;
    }

    /**
     * @return the icon
     */
    public String getIcon() {
        return icon;
    }

    /**
     * @param icon
     *            Updates the icon of the feed, or empty string to remove; this
     *            is the equivalent to a user profile pic.
     */
    public FeedOptions setIcon(String icon) {
        this.icon = icon;
        return this;
    }

    /**
     * @return the logo
     */
    public String getFeedLogo() {
        return logo;
    }

    /**
     * @param logo
     *            Updates the logo of the feed, or empty string to remove; this
     *            is the equivalent to a user background image.
     */
    public FeedOptions setLogo(String logo) {
        this.logo = logo;
        return this;
    }

}
