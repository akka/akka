/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi;

import akka.http.model.HttpCharsets$;
import akka.japi.Option;

/**
 * Contains a set of predefined charsets.
 */
public final class HttpCharsets {
    private HttpCharsets() {}

    public static final HttpCharset US_ASCII = akka.http.model.HttpCharsets.US$minusASCII();
    public static final HttpCharset ISO_8859_1 = akka.http.model.HttpCharsets.ISO$minus8859$minus1();
    public static final HttpCharset UTF_8 = akka.http.model.HttpCharsets.UTF$minus8();
    public static final HttpCharset UTF_16 = akka.http.model.HttpCharsets.UTF$minus16();
    public static final HttpCharset UTF_16BE = akka.http.model.HttpCharsets.UTF$minus16BE();
    public static final HttpCharset UTF_16LE = akka.http.model.HttpCharsets.UTF$minus16LE();

    /**
     * Registers a custom charset. Returns Some(newCharset) if the charset is supported by this JVM.
     * Returns None otherwise.
     */
    public static Option<HttpCharset> registerCustom(String value, String... aliases) {
        scala.Option<akka.http.model.HttpCharset> custom = akka.http.model.HttpCharset.custom(value, Util.<String, String>convertArray(aliases));
        if (custom.isDefined()) return Option.<HttpCharset>some(akka.http.model.HttpCharsets.register(custom.get()));
        else return Option.none();
    }

    /**
     * Returns Some(charset) if the charset with the given name was found and None otherwise.
     */
    public static Option<HttpCharset> lookup(String name) {
        return Util.lookupInRegistry(HttpCharsets$.MODULE$, name);
    }
}
