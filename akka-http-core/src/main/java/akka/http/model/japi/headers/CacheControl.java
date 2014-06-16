/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

/**
 *  Model for the `Cache-Control` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p6-cache-26#section-5.2
 */
public abstract class CacheControl extends akka.http.model.HttpHeader {
    public abstract Iterable<CacheDirective> getDirectives();

    public static CacheControl create(CacheDirective... directives) {
        return new akka.http.model.headers.Cache$minusControl(akka.http.model.japi.Util.<CacheDirective, akka.http.model.headers.CacheDirective>convertArray(directives));
    }
}
