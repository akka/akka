/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.model;

import akka.http.impl.util.JavaAccessors$;
import akka.http.scaladsl.model.UriJavaAccessor;
import akka.japi.Option;
import akka.parboiled2.ParserInput$;

import java.nio.charset.Charset;
import java.util.Map;

/**
 * Represents an Uri. Use the `withX` methods to create modified copies of a given instance.
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
     * Returns an `Iterable` of all query parameters of this Uri. Use the `parameterMap()`
     * method to filter out entries with duplicated keys.
     */
    public abstract Iterable<Map.Entry<String, String>> parameters();

    /**
     * Returns a key/value map of the query parameters of this Uri. Use
     * the `parameters()` method to return all parameters if keys may occur
     * multiple times.
     */
    public abstract Map<String, String> parameterMap();

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

    public static final akka.http.scaladsl.model.Uri.ParsingMode STRICT = UriJavaAccessor.pmStrict();
    public static final akka.http.scaladsl.model.Uri.ParsingMode RELAXED = UriJavaAccessor.pmRelaxed();
    public static final akka.http.scaladsl.model.Uri.ParsingMode RELAXED_WITH_RAW_QUERY = UriJavaAccessor.pmRelaxedWithRawQuery();

    /**
     * Creates a default Uri to be modified using the modification methods.
     */
    public static Uri create() {
        return JavaAccessors$.MODULE$.Uri(akka.http.scaladsl.model.Uri.Empty$.MODULE$);
    }

    /**
     * Returns a Uri created by parsing the given string representation.
     */
    public static Uri create(String uri) {
        return JavaAccessors$.MODULE$.Uri(akka.http.scaladsl.model.Uri.apply(uri));
    }

    /**
     * Returns a Uri created by parsing the given string representation and parsing-mode.
     */
    public static Uri create(String uri, akka.http.scaladsl.model.Uri.ParsingMode parsingMode) {
        return JavaAccessors$.MODULE$.Uri(akka.http.scaladsl.model.Uri.apply(ParserInput$.MODULE$.apply(uri), parsingMode));
    }

    /**
     * Returns a Uri created by parsing the given string representation, charset, and parsing-mode.
     */
    public static Uri create(String uri, Charset charset, akka.http.scaladsl.model.Uri.ParsingMode parsingMode) {
        return JavaAccessors$.MODULE$.Uri(akka.http.scaladsl.model.Uri.apply(ParserInput$.MODULE$.apply(uri), charset, parsingMode));
    }

}
