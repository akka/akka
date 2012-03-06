/**
 *   Copyright (C) 2011-2012 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config.impl;

import java.io.File;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import com.typesafe.config.ConfigException;
import com.typesafe.config.ConfigOrigin;

// it would be cleaner to have a class hierarchy for various origin types,
// but was hoping this would be enough simpler to be a little messy. eh.
final class SimpleConfigOrigin implements ConfigOrigin, Serializable {

    private static final long serialVersionUID = 1L;

    final private String description;
    final private int lineNumber;
    final private int endLineNumber;
    final private OriginType originType;
    final private String urlOrNull;
    final private List<String> commentsOrNull;

    protected SimpleConfigOrigin(String description, int lineNumber, int endLineNumber,
            OriginType originType,
 String urlOrNull, List<String> commentsOrNull) {
        this.description = description;
        this.lineNumber = lineNumber;
        this.endLineNumber = endLineNumber;
        this.originType = originType;
        this.urlOrNull = urlOrNull;
        this.commentsOrNull = commentsOrNull;
    }

    static SimpleConfigOrigin newSimple(String description) {
        return new SimpleConfigOrigin(description, -1, -1, OriginType.GENERIC, null, null);
    }

    static SimpleConfigOrigin newFile(String filename) {
        String url;
        try {
            url = (new File(filename)).toURI().toURL().toExternalForm();
        } catch (MalformedURLException e) {
            url = null;
        }
        return new SimpleConfigOrigin(filename, -1, -1, OriginType.FILE, url, null);
    }

    static SimpleConfigOrigin newURL(URL url) {
        String u = url.toExternalForm();
        return new SimpleConfigOrigin(u, -1, -1, OriginType.URL, u, null);
    }

    static SimpleConfigOrigin newResource(String resource, URL url) {
        return new SimpleConfigOrigin(resource, -1, -1, OriginType.RESOURCE,
                url != null ? url.toExternalForm() : null, null);
    }

    static SimpleConfigOrigin newResource(String resource) {
        return newResource(resource, null);
    }

    SimpleConfigOrigin setLineNumber(int lineNumber) {
        if (lineNumber == this.lineNumber && lineNumber == this.endLineNumber) {
            return this;
        } else {
            return new SimpleConfigOrigin(this.description, lineNumber, lineNumber,
                    this.originType, this.urlOrNull, this.commentsOrNull);
        }
    }

    SimpleConfigOrigin addURL(URL url) {
        return new SimpleConfigOrigin(this.description, this.lineNumber, this.endLineNumber,
                this.originType, url != null ? url.toExternalForm() : null, this.commentsOrNull);
    }

    SimpleConfigOrigin setComments(List<String> comments) {
        if (ConfigImplUtil.equalsHandlingNull(comments, this.commentsOrNull)) {
            return this;
        } else {
            return new SimpleConfigOrigin(this.description, this.lineNumber, this.endLineNumber,
                    this.originType, this.urlOrNull, comments);
        }
    }

    @Override
    public String description() {
        // not putting the URL in here for files and resources, because people
        // parsing "file: line" syntax would hit the ":" in the URL.
        if (lineNumber < 0) {
            return description;
        } else if (endLineNumber == lineNumber) {
            return description + ": " + lineNumber;
        } else {
            return description + ": " + lineNumber + "-" + endLineNumber;
        }
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof SimpleConfigOrigin) {
            SimpleConfigOrigin otherOrigin = (SimpleConfigOrigin) other;

            return this.description.equals(otherOrigin.description)
                    && this.lineNumber == otherOrigin.lineNumber
                    && this.endLineNumber == otherOrigin.endLineNumber
                    && this.originType == otherOrigin.originType
                    && ConfigImplUtil.equalsHandlingNull(this.urlOrNull, otherOrigin.urlOrNull);
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        int h = 41 * (41 + description.hashCode());
        h = 41 * (h + lineNumber);
        h = 41 * (h + endLineNumber);
        h = 41 * (h + originType.hashCode());
        if (urlOrNull != null)
            h = 41 * (h + urlOrNull.hashCode());
        return h;
    }

    @Override
    public String toString() {
        // the url is only really useful on top of description for resources
        if (originType == OriginType.RESOURCE && urlOrNull != null) {
            return "ConfigOrigin(" + description + "," + urlOrNull + ")";
        } else {
            return "ConfigOrigin(" + description + ")";
        }
    }

    @Override
    public String filename() {
        if (originType == OriginType.FILE) {
            return description;
        } else if (urlOrNull != null) {
            URL url;
            try {
                url = new URL(urlOrNull);
            } catch (MalformedURLException e) {
                return null;
            }
            if (url.getProtocol().equals("file")) {
                return url.getFile();
            } else {
                return null;
            }
        } else {
            return null;
        }
    }

    @Override
    public URL url() {
        if (urlOrNull == null) {
            return null;
        } else {
            try {
                return new URL(urlOrNull);
            } catch (MalformedURLException e) {
                return null;
            }
        }
    }

    @Override
    public String resource() {
        if (originType == OriginType.RESOURCE) {
            return description;
        } else {
            return null;
        }
    }

    @Override
    public int lineNumber() {
        return lineNumber;
    }

    @Override
    public List<String> comments() {
        if (commentsOrNull != null) {
            return commentsOrNull;
        } else {
            return Collections.emptyList();
        }
    }

    static final String MERGE_OF_PREFIX = "merge of ";

    private static SimpleConfigOrigin mergeTwo(SimpleConfigOrigin a, SimpleConfigOrigin b) {
        String mergedDesc;
        int mergedStartLine;
        int mergedEndLine;
        List<String> mergedComments;

        OriginType mergedType;
        if (a.originType == b.originType) {
            mergedType = a.originType;
        } else {
            mergedType = OriginType.GENERIC;
        }

        // first use the "description" field which has no line numbers
        // cluttering it.
        String aDesc = a.description;
        String bDesc = b.description;
        if (aDesc.startsWith(MERGE_OF_PREFIX))
            aDesc = aDesc.substring(MERGE_OF_PREFIX.length());
        if (bDesc.startsWith(MERGE_OF_PREFIX))
            bDesc = bDesc.substring(MERGE_OF_PREFIX.length());

        if (aDesc.equals(bDesc)) {
            mergedDesc = aDesc;

            if (a.lineNumber < 0)
                mergedStartLine = b.lineNumber;
            else if (b.lineNumber < 0)
                mergedStartLine = a.lineNumber;
            else
                mergedStartLine = Math.min(a.lineNumber, b.lineNumber);

            mergedEndLine = Math.max(a.endLineNumber, b.endLineNumber);
        } else {
            // this whole merge song-and-dance was intended to avoid this case
            // whenever possible, but we've lost. Now we have to lose some
            // structured information and cram into a string.

            // description() method includes line numbers, so use it instead
            // of description field.
            String aFull = a.description();
            String bFull = b.description();
            if (aFull.startsWith(MERGE_OF_PREFIX))
                aFull = aFull.substring(MERGE_OF_PREFIX.length());
            if (bFull.startsWith(MERGE_OF_PREFIX))
                bFull = bFull.substring(MERGE_OF_PREFIX.length());

            mergedDesc = MERGE_OF_PREFIX + aFull + "," + bFull;

            mergedStartLine = -1;
            mergedEndLine = -1;
        }

        String mergedURL;
        if (ConfigImplUtil.equalsHandlingNull(a.urlOrNull, b.urlOrNull)) {
            mergedURL = a.urlOrNull;
        } else {
            mergedURL = null;
        }

        if (ConfigImplUtil.equalsHandlingNull(a.commentsOrNull, b.commentsOrNull)) {
            mergedComments = a.commentsOrNull;
        } else {
            mergedComments = new ArrayList<String>();
            if (a.commentsOrNull != null)
                mergedComments.addAll(a.commentsOrNull);
            if (b.commentsOrNull != null)
                mergedComments.addAll(b.commentsOrNull);
        }

        return new SimpleConfigOrigin(mergedDesc, mergedStartLine, mergedEndLine, mergedType,
                mergedURL, mergedComments);
    }

    private static int similarity(SimpleConfigOrigin a, SimpleConfigOrigin b) {
        int count = 0;

        if (a.originType == b.originType)
            count += 1;

        if (a.description.equals(b.description)) {
            count += 1;

            // only count these if the description field (which is the file
            // or resource name) also matches.
            if (a.lineNumber == b.lineNumber)
                count += 1;
            if (a.endLineNumber == b.endLineNumber)
                count += 1;
            if (ConfigImplUtil.equalsHandlingNull(a.urlOrNull, b.urlOrNull))
                count += 1;
        }

        return count;
    }

    // this picks the best pair to merge, because the pair has the most in
    // common. we want to merge two lines in the same file rather than something
    // else with one of the lines; because two lines in the same file can be
    // better consolidated.
    private static SimpleConfigOrigin mergeThree(SimpleConfigOrigin a, SimpleConfigOrigin b,
            SimpleConfigOrigin c) {
        if (similarity(a, b) >= similarity(b, c)) {
            return mergeTwo(mergeTwo(a, b), c);
        } else {
            return mergeTwo(a, mergeTwo(b, c));
        }
    }

    static ConfigOrigin mergeOrigins(Collection<? extends ConfigOrigin> stack) {
        if (stack.isEmpty()) {
            throw new ConfigException.BugOrBroken("can't merge empty list of origins");
        } else if (stack.size() == 1) {
            return stack.iterator().next();
        } else if (stack.size() == 2) {
            Iterator<? extends ConfigOrigin> i = stack.iterator();
            return mergeTwo((SimpleConfigOrigin) i.next(), (SimpleConfigOrigin) i.next());
        } else {
            List<SimpleConfigOrigin> remaining = new ArrayList<SimpleConfigOrigin>();
            for (ConfigOrigin o : stack) {
                remaining.add((SimpleConfigOrigin) o);
            }
            while (remaining.size() > 2) {
                SimpleConfigOrigin c = remaining.get(remaining.size() - 1);
                remaining.remove(remaining.size() - 1);
                SimpleConfigOrigin b = remaining.get(remaining.size() - 1);
                remaining.remove(remaining.size() - 1);
                SimpleConfigOrigin a = remaining.get(remaining.size() - 1);
                remaining.remove(remaining.size() - 1);

                SimpleConfigOrigin merged = mergeThree(a, b, c);

                remaining.add(merged);
            }

            // should be down to either 1 or 2
            return mergeOrigins(remaining);
        }
    }
}
