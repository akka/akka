/**
 *   Copyright (C) 2011-2012 Typesafe Inc. <http://typesafe.com>
 */
package com.typesafe.config.impl;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import com.typesafe.config.ConfigException;


/** This is public just for the "config" package to use, don't touch it */
final public class ConfigImplUtil {
    static boolean equalsHandlingNull(Object a, Object b) {
        if (a == null && b != null)
            return false;
        else if (a != null && b == null)
            return false;
        else if (a == b) // catches null == null plus optimizes identity case
            return true;
        else
            return a.equals(b);
    }

    /**
     * This is public ONLY for use by the "config" package, DO NOT USE this ABI
     * may change.
     */
    public static String renderJsonString(String s) {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < s.length(); ++i) {
            char c = s.charAt(i);
            switch (c) {
            case '"':
                sb.append("\\\"");
                break;
            case '\\':
                sb.append("\\\\");
                break;
            case '\n':
                sb.append("\\n");
                break;
            case '\b':
                sb.append("\\b");
                break;
            case '\f':
                sb.append("\\f");
                break;
            case '\r':
                sb.append("\\r");
                break;
            case '\t':
                sb.append("\\t");
                break;
            default:
                if (Character.isISOControl(c))
                    sb.append(String.format("\\u%04x", (int) c));
                else
                    sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    static boolean isWhitespace(int codepoint) {
        switch (codepoint) {
        // try to hit the most common ASCII ones first, then the nonbreaking
        // spaces that Java brokenly leaves out of isWhitespace.
        case ' ':
        case '\n':
        case '\u00A0':
        case '\u2007':
        case '\u202F':
            return true;
        default:
            return Character.isWhitespace(codepoint);
        }
    }

    /** This is public just for the "config" package to use, don't touch it! */
    public static String unicodeTrim(String s) {
        // this is dumb because it looks like there aren't any whitespace
        // characters that need surrogate encoding. But, points for
        // pedantic correctness! It's future-proof or something.
        // String.trim() actually is broken, since there are plenty of
        // non-ASCII whitespace characters.
        final int length = s.length();
        if (length == 0)
            return s;

        int start = 0;
        while (start < length) {
            char c = s.charAt(start);
            if (c == ' ' || c == '\n') {
                start += 1;
            } else {
                int cp = s.codePointAt(start);
                if (isWhitespace(cp))
                    start += Character.charCount(cp);
                else
                    break;
            }
        }

        int end = length;
        while (end > start) {
            char c = s.charAt(end - 1);
            if (c == ' ' || c == '\n') {
                --end;
            } else {
                int cp;
                int delta;
                if (Character.isLowSurrogate(c)) {
                    cp = s.codePointAt(end - 2);
                    delta = 2;
                } else {
                    cp = s.codePointAt(end - 1);
                    delta = 1;
                }
                if (isWhitespace(cp))
                    end -= delta;
                else
                    break;
            }
        }
        return s.substring(start, end);
    }

    /** This is public just for the "config" package to use, don't touch it! */
    public static ConfigException extractInitializerError(ExceptionInInitializerError e) {
        Throwable cause = e.getCause();
        if (cause != null && cause instanceof ConfigException) {
            return (ConfigException) cause;
        } else {
            throw e;
        }
    }

    static File urlToFile(URL url) {
        // this isn't really right, clearly, but not sure what to do.
        try {
            // this will properly handle hex escapes, etc.
            return new File(url.toURI());
        } catch (URISyntaxException e) {
            // this handles some stuff like file:///c:/Whatever/
            // apparently but mangles handling of hex escapes
            return new File(url.getPath());
        }
    }

    /**
     * This is public ONLY for use by the "config" package, DO NOT USE this ABI
     * may change. You can use the version in ConfigUtil instead.
     */
    public static String joinPath(String... elements) {
        return (new Path(elements)).render();
    }

    /**
     * This is public ONLY for use by the "config" package, DO NOT USE this ABI
     * may change. You can use the version in ConfigUtil instead.
     */
    public static String joinPath(List<String> elements) {
        return joinPath(elements.toArray(new String[0]));
    }

    /**
     * This is public ONLY for use by the "config" package, DO NOT USE this ABI
     * may change. You can use the version in ConfigUtil instead.
     */
    public static List<String> splitPath(String path) {
        Path p = Path.newPath(path);
        List<String> elements = new ArrayList<String>();
        while (p != null) {
            elements.add(p.first());
            p = p.remainder();
        }
        return elements;
    }
}
