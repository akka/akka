/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import akka.http.javadsl.model.HttpMethod;

/**
 *  Model for the `Allow` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p2-semantics-26#section-7.4.1
 */
public abstract class Allow extends akka.http.scaladsl.model.HttpHeader {
    public abstract Iterable<HttpMethod> getMethods();

    public static Allow create(HttpMethod... methods) {
        return new akka.http.scaladsl.model.headers.Allow(akka.http.impl.util.Util.<HttpMethod, akka.http.scaladsl.model.HttpMethod>convertArray(methods));
    }
}
