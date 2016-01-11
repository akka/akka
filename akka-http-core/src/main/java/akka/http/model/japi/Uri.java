/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi;

import akka.japi.Option;
import akka.parboiled2.ParserInput$;

import java.nio.charset.Charset;
import java.util.Map;

/**
 * Represents a Uri. Use the `withX` methods to create modified copies of a given instance.
 */
public abstract class Uri {
    /**
     * Returns if this is an absolute Uri.
     */
    public abstract boolean isAbsolute();

    /**
     * Returns if this is a relative Uri.
     */
    public abstract boolean isRelative();

    /**
     * Returns if this is an empty Uri.
     */
    public abstract boolean isEmpty();

    /**
     * Returns the scheme of this Uri.
     */
    public abstract String scheme();

    /**
     * Returns the Host of this Uri.
     */
    public abstract Host host();

    /**
     * Returns the port of this Uri.
     */
    public abstract int port();

    /**
     * Returns the user-info of this Uri.
     */
    public abstract String userInfo();

    /**
     * Returns a String representation of the path of this Uri.
     */
    public abstract String path();

    /**
     * Returns the the path segments of this Uri as an Iterable.
     */
    public abstract Iterable<String> pathSegments();

    /**
     * Returns a String representation of the query of this Uri.
     */
    public abstract String queryString();

    /**
     * Looks up a query parameter of this Uri.
     */
    public abstract Option<String> parameter(String key);

    /**
     * Returns if the query of this Uri contains a parameter with the given key.
     */
    public abstract boolean containsParameter(String key);

    /**
     * Returns an Iterable of all query parameters of this Uri.
     */
    public abstract Iterable<Parameter> parameters();

    /**
     * Returns a key/value map of the query parameters of this Uri. Use
     * the `parameters()` method to returns all parameters if keys may occur
     * multiple times.
     */
    public abstract Map<String, String> parameterMap();

    public static interface Parameter {
        String key();
        String value();
    }

    /**
     * Returns the fragment part of this Uri.
     */
    public abstract Option<String> fragment();

    /**
     * Returns a copy of this instance with a new scheme.
     */
    public abstract Uri scheme(String scheme);

    /**
     * Returns a copy of this instance with a new Host.
     */
    public abstract Uri host(Host host);

    /**
     * Returns a copy of this instance with a new host.
     */
    public abstract Uri host(String host);

    /**
     * Returns a copy of this instance with a new port.
     */
    public abstract Uri port(int port);

    /**
     * Returns a copy of this instance with new user-info.
     */
    public abstract Uri userInfo(String userInfo);

    /**
     * Returns a copy of this instance with a new path.
     */
    public abstract Uri path(String path);

    /**
     * Returns a copy of this instance with a path segment added at the end.
     */
    public abstract Uri addPathSegment(String segment);

    /**
     * Returns a copy of this instance with a new query.
     */
    public abstract Uri query(String query);

    /**
     * Returns a copy of this instance that is relative.
     */
    public abstract Uri toRelative();

    /**
     * Returns a copy of this instance with a query parameter added.
     */
    public abstract Uri addParameter(String key, String value);

    /**
     * Returns a copy of this instance with a new fragment.
     */
    public abstract Uri fragment(String fragment);

    /**
     * Returns a copy of this instance with a new optional fragment.
     */
    public abstract Uri fragment(Option<String> fragment);

    public static final akka.http.model.Uri.ParsingMode STRICT = akka.http.model.Uri$ParsingMode$Strict$.MODULE$;
    public static final akka.http.model.Uri.ParsingMode RELAXED = akka.http.model.Uri$ParsingMode$Relaxed$.MODULE$;
    public static final akka.http.model.Uri.ParsingMode RELAXED_WITH_RAW_QUERY = akka.http.model.Uri$ParsingMode$RelaxedWithRawQuery$.MODULE$;

    /**
     * Creates a default Uri to be modified using the modification methods.
     */
    public static Uri create() {
        return Accessors$.MODULE$.Uri(akka.http.model.Uri.Empty$.MODULE$);
    }

    /**
     * Returns a Uri created by parsing the given string representation.
     */
    public static Uri create(String uri) {
        return Accessors$.MODULE$.Uri(akka.http.model.Uri.apply(uri));
    }

    /**
     * Returns a Uri created by parsing the given string representation and parsing-mode.
     */
    public static Uri create(String uri, akka.http.model.Uri.ParsingMode parsingMode) {
        return Accessors$.MODULE$.Uri(akka.http.model.Uri.apply(ParserInput$.MODULE$.apply(uri), parsingMode));
    }

    /**
     * Returns a Uri created by parsing the given string representation, charset, and parsing-mode.
     */
    public static Uri create(String uri, Charset charset, akka.http.model.Uri.ParsingMode parsingMode) {
        return Accessors$.MODULE$.Uri(akka.http.model.Uri.apply(ParserInput$.MODULE$.apply(uri), charset, parsingMode));
    }

}
