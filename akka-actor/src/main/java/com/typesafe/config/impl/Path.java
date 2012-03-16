/**
 *   Copyright (C) 2011-2012 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config.impl;

import java.io.Serializable;
import java.util.Iterator;
import java.util.List;

import com.typesafe.config.ConfigException;

final class Path implements Serializable {

    private static final long serialVersionUID = 1L;

    final private String first;
    final private Path remainder;

    Path(String first, Path remainder) {
        this.first = first;
        this.remainder = remainder;
    }

    Path(String... elements) {
        if (elements.length == 0)
            throw new ConfigException.BugOrBroken("empty path");
        this.first = elements[0];
        if (elements.length > 1) {
            PathBuilder pb = new PathBuilder();
            for (int i = 1; i < elements.length; ++i) {
                pb.appendKey(elements[i]);
            }
            this.remainder = pb.result();
        } else {
            this.remainder = null;
        }
    }

    // append all the paths in the list together into one path
    Path(List<Path> pathsToConcat) {
        if (pathsToConcat.isEmpty())
            throw new ConfigException.BugOrBroken("empty path");

        Iterator<Path> i = pathsToConcat.iterator();
        Path firstPath = i.next();
        this.first = firstPath.first;

        PathBuilder pb = new PathBuilder();
        if (firstPath.remainder != null) {
            pb.appendPath(firstPath.remainder);
        }
        while (i.hasNext()) {
            pb.appendPath(i.next());
        }
        this.remainder = pb.result();
    }

    String first() {
        return first;
    }

    /**
     *
     * @return path minus the first element or null if no more elements
     */
    Path remainder() {
        return remainder;
    }

    /**
     *
     * @return path minus the last element or null if we have just one element
     */
    Path parent() {
        if (remainder == null)
            return null;

        PathBuilder pb = new PathBuilder();
        Path p = this;
        while (p.remainder != null) {
            pb.appendKey(p.first);
            p = p.remainder;
        }
        return pb.result();
    }

    /**
     *
     * @return last element in the path
     */
    String last() {
        Path p = this;
        while (p.remainder != null) {
            p = p.remainder;
        }
        return p.first;
    }

    Path prepend(Path toPrepend) {
        PathBuilder pb = new PathBuilder();
        pb.appendPath(toPrepend);
        pb.appendPath(this);
        return pb.result();
    }

    int length() {
        int count = 1;
        Path p = remainder;
        while (p != null) {
            count += 1;
            p = p.remainder;
        }
        return count;
    }

    Path subPath(int removeFromFront) {
        int count = removeFromFront;
        Path p = this;
        while (p != null && count > 0) {
            count -= 1;
            p = p.remainder;
        }
        return p;
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof Path) {
            Path that = (Path) other;
            return this.first.equals(that.first)
                    && ConfigImplUtil.equalsHandlingNull(this.remainder,
                            that.remainder);
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return 41 * (41 + first.hashCode())
                + (remainder == null ? 0 : remainder.hashCode());
    }

    // this doesn't have a very precise meaning, just to reduce
    // noise from quotes in the rendered path for average cases
    static boolean hasFunkyChars(String s) {
        int length = s.length();

        if (length == 0)
            return false;

        // if the path starts with something that could be a number,
        // we need to quote it because the number could be invalid,
        // for example it could be a hyphen with no digit afterward
        // or the exponent "e" notation could be mangled.
        char first = s.charAt(0);
        if (!(Character.isLetter(first)))
            return true;

        for (int i = 1; i < length; ++i) {
            char c = s.charAt(i);

            if (Character.isLetterOrDigit(c) || c == '-' || c == '_')
                continue;
            else
                return true;
        }
        return false;
    }

    private void appendToStringBuilder(StringBuilder sb) {
        if (hasFunkyChars(first) || first.isEmpty())
            sb.append(ConfigImplUtil.renderJsonString(first));
        else
            sb.append(first);
        if (remainder != null) {
            sb.append(".");
            remainder.appendToStringBuilder(sb);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Path(");
        appendToStringBuilder(sb);
        sb.append(")");
        return sb.toString();
    }

    /**
     * toString() is a debugging-oriented version while this is an
     * error-message-oriented human-readable one.
     */
    String render() {
        StringBuilder sb = new StringBuilder();
        appendToStringBuilder(sb);
        return sb.toString();
    }

    static Path newKey(String key) {
        return new Path(key, null);
    }

    static Path newPath(String path) {
        return Parser.parsePath(path);
    }
}
