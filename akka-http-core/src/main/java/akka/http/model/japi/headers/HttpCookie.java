/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

import akka.http.model.japi.DateTime;
import akka.http.model.japi.Util;
import akka.japi.Option;

public abstract class HttpCookie {
    public abstract String name();
    public abstract String content();

    public abstract Option<DateTime> getExpires();
    public abstract Option<Long> getMaxAge();
    public abstract Option<String> getDomain();
    public abstract Option<String> getPath();
    public abstract boolean secure();
    public abstract boolean httpOnly();
    public abstract Option<String> getExtension();

    public static HttpCookie create(String name, String content) {
        return new akka.http.model.headers.HttpCookie(
                name, content,
                Util.<akka.http.util.DateTime>scalaNone(), Util.scalaNone(), Util.<String>scalaNone(), Util.<String>scalaNone(),
                false, false,
                Util.<String>scalaNone());
    }
    @SuppressWarnings("unchecked")
    public static HttpCookie create(
        String name,
        String content,
        Option<DateTime> expires,
        Option<Long> maxAge,
        Option<String> domain,
        Option<String> path,
        boolean secure,
        boolean httpOnly,
        Option<String> extension) {
        return new akka.http.model.headers.HttpCookie(
                name, content,
                Util.<DateTime, akka.http.util.DateTime>convertOptionToScala(expires),
                ((Option<Object>) (Option) maxAge).asScala(),
                domain.asScala(),
                path.asScala(),
                secure,
                httpOnly,
                extension.asScala());
    }
}
