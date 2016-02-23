/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model;

import akka.http.impl.util.Util;
import akka.http.scaladsl.model.HttpCharsets$;

import java.util.Optional;

/**
 * Contains a set of predefined charsets.
 */
public final class HttpCharsets {
    private HttpCharsets() {}

    public static final HttpCharset US_ASCII = akka.http.scaladsl.model.HttpCharsets.US$minusASCII();
    public static final HttpCharset ISO_8859_1 = akka.http.scaladsl.model.HttpCharsets.ISO$minus8859$minus1();
    public static final HttpCharset UTF_8 = akka.http.scaladsl.model.HttpCharsets.UTF$minus8();
    public static final HttpCharset UTF_16 = akka.http.scaladsl.model.HttpCharsets.UTF$minus16();
    public static final HttpCharset UTF_16BE = akka.http.scaladsl.model.HttpCharsets.UTF$minus16BE();
    public static final HttpCharset UTF_16LE = akka.http.scaladsl.model.HttpCharsets.UTF$minus16LE();

    /**
     * Create and return a custom charset.
     */
    public static HttpCharset custom(String value, String... aliases) {
        return akka.http.scaladsl.model.HttpCharset.custom(value, Util.<String, String>convertArray(aliases));
    }

    /**
     * Returns Some(charset) if the charset with the given name was found and None otherwise.
     */
    public static Optional<HttpCharset> lookup(String name) {
        return Util.<HttpCharset, akka.http.scaladsl.model.HttpCharset>lookupInRegistry(HttpCharsets$.MODULE$, name);
    }
}
