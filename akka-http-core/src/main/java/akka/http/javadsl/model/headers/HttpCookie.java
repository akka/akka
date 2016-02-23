/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import akka.http.javadsl.model.DateTime;
import akka.http.impl.util.Util;
import scala.compat.java8.OptionConverters;

import java.util.Optional;
import java.util.OptionalLong;

public abstract class HttpCookie {
    public abstract String name();
    public abstract String value();
    public abstract HttpCookiePair pair();

    public abstract Optional<DateTime> getExpires();
    public abstract OptionalLong getMaxAge();
    public abstract Optional<String> getDomain();
    public abstract Optional<String> getPath();
    public abstract boolean secure();
    public abstract boolean httpOnly();
    public abstract Optional<String> getExtension();

    public static HttpCookie create(String name, String value) {
        return new akka.http.scaladsl.model.headers.HttpCookie(
                name, value,
                Util.<akka.http.scaladsl.model.DateTime>scalaNone(), Util.scalaNone(), Util.<String>scalaNone(), Util.<String>scalaNone(),
                false, false,
                Util.<String>scalaNone());
    }
    public static HttpCookie create(String name, String value, Optional<String> domain, Optional<String> path) {
        return new akka.http.scaladsl.model.headers.HttpCookie(
                name, value,
                Util.<akka.http.scaladsl.model.DateTime>scalaNone(), Util.scalaNone(),
                OptionConverters.toScala(domain), OptionConverters.toScala(path),
                false, false,
                Util.<String>scalaNone());
    }
    @SuppressWarnings("unchecked")
    public static HttpCookie create(
        String name,
        String value,
        Optional<DateTime> expires,
        OptionalLong maxAge,
        Optional<String> domain,
        Optional<String> path,
        boolean secure,
        boolean httpOnly,
        Optional<String> extension) {
        return new akka.http.scaladsl.model.headers.HttpCookie(
                name, value,
                Util.<DateTime, akka.http.scaladsl.model.DateTime>convertOptionalToScala(expires),
                OptionConverters.toScala(maxAge),
                OptionConverters.toScala(domain),
                OptionConverters.toScala(path),
                secure,
                httpOnly,
                OptionConverters.toScala(extension));
    }

    /**
     * Returns a copy of this HttpCookie instance with the given expiration set.
     */
    public abstract HttpCookie withExpires(DateTime dateTime);

    /**
     * Returns a copy of this HttpCookie instance with the given max age set.
     */
    public abstract HttpCookie withMaxAge(long maxAge);

    /**
     * Returns a copy of this HttpCookie instance with the given domain set.
     */
    public abstract HttpCookie withDomain(String domain);

    /**
     * Returns a copy of this HttpCookie instance with the given path set.
     */
    public abstract HttpCookie withPath(String path);

    /**
     * Returns a copy of this HttpCookie instance with the given secure flag set.
     */
    public abstract HttpCookie withSecure(boolean secure);

    /**
     * Returns a copy of this HttpCookie instance with the given http-only flag set.
     */
    public abstract HttpCookie withHttpOnly(boolean httpOnly);

    /**
     * Returns a copy of this HttpCookie instance with the given extension set.
     */
    public abstract HttpCookie withExtension(String extension);
}
