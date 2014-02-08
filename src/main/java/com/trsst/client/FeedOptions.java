package com.trsst.client;

/**
 * Builder class for updating a feed and/or creating an entry.
 * 
 * @author mpowers
 */
public class FeedOptions {

    String base;
    String name;
    String email;
    String title;
    String subtitle;
    String icon;
    String logo;
    boolean asIcon;
    boolean asLogo;

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
        asIcon = false;
        asLogo = false;
    }

    /**
     * @return this feed's home base url.
     */
    public String getFeedBase() {
        return base;
    }

    /**
     * @param name
     *            Specify the home base URL where this feed is permanently
     *            hosted.
     */
    public FeedOptions setBase(String base) {
        this.base = base;
        return this;
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
    public String getFeedIcon() {
        return icon;
    }

    /**
     * @param asIcon
     *            Uses the entry attachment as an icon.
     */
    public FeedOptions setAsIcon(boolean asIcon) {
        this.asIcon = asIcon;
        return this;
    }

    /**
     * @param icon
     *            Updates the icon of the feed, or empty string to remove; this
     *            is the equivalent to a user profile pic.
     */
    public FeedOptions setIconURL(String icon) {
        this.asIcon = false;
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
     * @param asLogo
     *            Uses the entry attachment as a logo.
     */
    public FeedOptions setAsLogo(boolean asLogo) {
        this.asLogo = asLogo;
        return this;
    }

    /**
     * @param logo
     *            Updates the logo of the feed, or empty string to remove; this
     *            is the equivalent to a user background image.
     */
    public FeedOptions setLogoURL(String logo) {
        this.asLogo = false;
        this.logo = logo;
        return this;
    }

}
