/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.model.headers;

import akka.http.javadsl.model.DateTime;
import akka.http.impl.util.Util;
import akka.japi.Option;

public abstract class HttpCookie {
    public abstract String name();
    public abstract String value();
    public abstract HttpCookiePair pair();

    public abstract Option<DateTime> getExpires();
    public abstract Option<Long> getMaxAge();
    public abstract Option<String> getDomain();
    public abstract Option<String> getPath();
    public abstract boolean secure();
    public abstract boolean httpOnly();
    public abstract Option<String> getExtension();

    public static HttpCookie create(String name, String value) {
        return new akka.http.scaladsl.model.headers.HttpCookie(
                name, value,
                Util.<akka.http.scaladsl.model.DateTime>scalaNone(), Util.scalaNone(), Util.<String>scalaNone(), Util.<String>scalaNone(),
                false, false,
                Util.<String>scalaNone());
    }
    @SuppressWarnings("unchecked")
    public static HttpCookie create(
        String name,
        String value,
        Option<DateTime> expires,
        Option<Long> maxAge,
        Option<String> domain,
        Option<String> path,
        boolean secure,
        boolean httpOnly,
        Option<String> extension) {
        return new akka.http.scaladsl.model.headers.HttpCookie(
                name, value,
                Util.<DateTime, akka.http.scaladsl.model.DateTime>convertOptionToScala(expires),
                ((Option<Object>) (Option) maxAge).asScala(),
                domain.asScala(),
                path.asScala(),
                secure,
                httpOnly,
                extension.asScala());
    }
}
