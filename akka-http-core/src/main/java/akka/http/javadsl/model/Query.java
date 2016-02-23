/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model;

import akka.http.impl.model.JavaQuery;
import akka.http.scaladsl.model.*;
import akka.japi.Pair;
import akka.parboiled2.CharPredicate;
import akka.parboiled2.ParserInput$;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public abstract class Query {
    /**
     * Returns the value of the first parameter with the given key if it exists.
     */
    public abstract Optional<String> get(String key);

    /**
     * Returns the value of the first parameter with the given key or the provided default value.
     */
    public abstract String getOrElse(String key, String _default);

    /**
     * Returns the value of all parameters with the given key.
     */
    public abstract List<String> getAll(String key);

    /**
     * Returns a `List` of all parameters of this Query. Use the `toMap()`
     * method to filter out entries with duplicated keys.
     */
    public abstract List<Pair<String, String>> toList();

    /**
    * Returns a key/value map of the parameters of this Query. Use
    * the `toList()` method to return all parameters if keys may occur
    * multiple times.
    */
    public abstract Map<String, String> toMap();

    /**
     * Returns a `Map` of all parameters of this Query. Use the `toMap()`
     * method to filter out entries with duplicated keys.
     */
    public abstract Map<String, List<String>> toMultiMap();

    /**
     * Returns a copy of this instance with a query parameter added.
     */
    public abstract Query withParam(String key, String value);

    /**
     * Renders this Query into its string representation using the given charset.
     */
    public abstract String render(HttpCharset charset);

    /**
     * Renders this Query into its string representation using the given charset and char predicate.
     */
    public abstract String render(HttpCharset charset, CharPredicate keep);

    /**
     * Returns an empty Query.
     */
    public static final Query EMPTY = new JavaQuery(UriJavaAccessor.emptyQuery());

    /**
     * Returns a Query created by parsing the given undecoded string representation.
     */
    public static Query create(String rawQuery) {
        return new JavaQuery(akka.http.scaladsl.model.Uri.Query$.MODULE$.apply(rawQuery));
    }

    /**
     * Returns a Query created by parsing the given undecoded string representation with the provided parsing mode.
     */
    public static Query create(String rawQuery, akka.http.scaladsl.model.Uri.ParsingMode parsingMode) {
        return new JavaQuery(UriJavaAccessor.queryApply(rawQuery, parsingMode));
    }

    /**
     * Returns a Query created by parsing the given undecoded string representation with the provided charset and parsing mode.
     */
    public static Query create(String rawQuery, Charset charset, akka.http.scaladsl.model.Uri.ParsingMode parsingMode) {
        return new JavaQuery(akka.http.scaladsl.model.Uri.Query$.MODULE$.apply(ParserInput$.MODULE$.apply(rawQuery), charset, parsingMode));
    }

    /**
     * Returns a Query from the given parameters.
     */
    @SafeVarargs
    public static Query create(Pair<String, String>... params) {
        return new JavaQuery(UriJavaAccessor.queryApply(params));
    }

    /**
     * Returns a Query from the given parameters.
     */
    public static Query create(Map<String, String> params) {
        return new JavaQuery(UriJavaAccessor.queryApply(params));
    }
}
