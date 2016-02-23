/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 *  Model for the `Cache-Control` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p6-cache-26#section-5.2
 */
public abstract class CacheControl extends akka.http.scaladsl.model.HttpHeader {
    public abstract Iterable<CacheDirective> getDirectives();

    public static CacheControl create(CacheDirective... directives) {
        return new akka.http.scaladsl.model.headers.Cache$minusControl(akka.http.impl.util.Util.<CacheDirective, akka.http.scaladsl.model.headers.CacheDirective>convertArray(directives));
    }
}
