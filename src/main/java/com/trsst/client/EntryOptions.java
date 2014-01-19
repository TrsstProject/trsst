package com.trsst.client;

import java.security.PublicKey;
import java.util.Date;

/**
 * Builder class for updating a feed and/or creating an entry.
 * 
 * @author mpowers
 */
public class EntryOptions {

    String status;
    String verb;
    Date publish;
    String body;
    String url;
    String[] mentions;
    String[] tags;
    String mimetype;
    byte[] content;
    PublicKey recipientKey;
    EntryOptions publicOptions;

    /**
     * Create empty default post options. By default, no entry is created, and
     * no feed settings are updated, although a new feed may be created if it
     * does not already exist.
     */
    public EntryOptions() {
    }

    /**
     * Convenience to reset these options to original state for reuse.
     */
    public void reset() {
        status = null;
        verb = null;
        publish = null;
        body = null;
        url = null;
        mentions = null;
        tags = null;
        mimetype = null;
        content = null;
        recipientKey = null;
        publicOptions = null;
    }

    /**
     * @return the status
     */
    public String getStatus() {
        return status;
    }

    /**
     * @param status
     *            A short text string no longer than 250 characters with no
     *            markup.
     */
    public EntryOptions setStatus(String status) {
        this.status = status;
        return this;
    }

    /**
     * @return the verb
     */
    public String getVerb() {
        return verb;
    }

    /**
     * @param verb
     *            An activity streams verb; if unspecified, "post" is implicit.
     */
    public EntryOptions setVerb(String verb) {
        this.verb = verb;
        return this;
    }

    /**
     * @return the publish date
     */
    public Date getPublish() {
        return publish;
    }

    /**
     * @param publish
     *            The date on which this entry is publicly available, which may
     *            be in the future.
     */
    public EntryOptions setPublish(Date publish) {
        this.publish = publish;
        return this;
    }

    /**
     * @return the body
     */
    public String getBody() {
        return body;
    }

    /**
     * @param body
     *            An arbitrarily long text string that may be formatted in
     *            markdown; no HTML is allowed.
     */
    public EntryOptions setBody(String body) {
        this.body = body;
        return this;
    }

    /**
     * @return the mentions
     */
    public String[] getMentions() {
        return mentions;
    }

    /**
     * @param mentions
     *            Zero or more feed ids, or aliases to feed ids in the form of
     *            alias@homeserver
     */
    public EntryOptions setMentions(String[] mentions) {
        this.mentions = mentions;
        return this;
    }

    /**
     * @return the tags
     */
    public String[] getTags() {
        return tags;
    }

    /**
     * @param tags
     *            Zero or more tags (aka hashtags but without the hash); these
     *            are equivalent to atom categories.
     */
    public EntryOptions setTags(String[] tags) {
        this.tags = tags;
        return this;
    }

    /**
     * @return the mimetype
     */
    public String getMimetype() {
        return mimetype;
    }

    /**
     * @return the content
     */
    public byte[] getContentData() {
        return content;
    }

    /**
     * @param content
     *            Optional binary content to be uploaded and hosted.
     * @throws IllegalArgumentException
     *             if contentUrl is already set.
     */
    public EntryOptions setContentData(byte[] content, String mimetype) {
        if (this.url != null) {
            throw new IllegalArgumentException(
                    "Cannot have set both url and data");
        }
        this.content = content;
        this.mimetype = mimetype;
        return this;
    }

    /**
     * @return the url
     */
    public String getContentUrl() {
        return url;
    }

    /**
     * Sets the optional url to share.
     * 
     * @param content
     *            Optional url to share.
     * @throws IllegalArgumentException
     *             if contentData is already set.
     */
    public EntryOptions setContentUrl(String url) {
        if (this.content != null) {
            throw new IllegalArgumentException(
                    "Cannot have set both url and data");
        }
        this.url = url;
        return this;
    }

    /**
     * @return the recipientKey
     */
    public PublicKey getRecipientKey() {
        return recipientKey;
    }

    /**
     * @param publicOptions
     *            publicly-readable options for the post that contains the
     *            encrypted entry data
     * @param recipientKey
     *            encrypts this entry using the specified public key so that
     *            only that key's owner can read it.
     */
    public EntryOptions encryptWith(PublicKey recipientKey,
            EntryOptions publicOptions) {
        this.publicOptions = publicOptions;
        this.recipientKey = recipientKey;
        return this;
    }

}
