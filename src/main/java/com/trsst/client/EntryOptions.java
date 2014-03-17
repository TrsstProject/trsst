package com.trsst.client;

import java.security.PrivateKey;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

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
    String[] recipientIds;
    PrivateKey[] decryptionKeys;
    EntryOptions publicOptions;
    private List<String> mimetype = new LinkedList<String>();
    private List<byte[]> content = new LinkedList<byte[]>();

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
        recipientIds = null;
        decryptionKeys = null;
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
     * @return the mimetype parallel array
     */
    public String[] getMimetypes() {
        return mimetype.toArray(new String[0]);
    }

    /**
     * @return the content parallel array
     */
    public byte[][] getContentData() {
        return content.toArray(new byte[0][]);
    }

    /**
     * @return the size of the content parallel arrays
     */
    public int getContentCount() {
        return content.size();
    }

    /**
     * @param content
     *            Optional binary content to be uploaded and hosted.
     * @throws IllegalArgumentException
     *             if contentUrl is already set.
     */
    public EntryOptions addContentData(byte[] content, String mimetype) {
        if (this.url != null) {
            throw new IllegalArgumentException(
                    "Cannot have set both url and data");
        }
        this.content.add(content);
        this.mimetype.add(mimetype);
        return this;
    }

    /**
     * @return the url
     */
    public String getContentUrl() {
        return url;
    }

    /**
     * Sets the optional url to share. Note: this will take precedence over any
     * content attachments, which will still be referenced via an enclosure
     * link.
     * 
     * @param content
     *            Optional url to share.
     */
    public EntryOptions setContentUrl(String url) {
        this.url = url;
        return this;
    }

    /**
     * @return the recipientKey
     */
    public String[] getRecipientKeys() {
        return recipientIds;
    }

    /**
     * @param publicOptions
     *            publicly-readable options for the post that contains the
     *            encrypted entry data
     * @param recipientKey
     *            encrypts this entry using the specified public key so that
     *            only that key's owner can read it.
     */
    public EntryOptions encryptFor(String[] recipientKey,
            EntryOptions publicOptions) {
        this.publicOptions = publicOptions;
        this.recipientIds = recipientKey;
        return this;
    }

    /**
     * @param publicOptions
     *            publicly-readable options for the post that contains the
     *            encrypted entry data
     * @param decryptionKeys
     *            decrypts any encrypted entries using each of the specified
     *            private keys
     */
    public EntryOptions decryptWith(PrivateKey[] decryptionKeys) {
        this.decryptionKeys = decryptionKeys;
        return this;
    }

}
