/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

import akka.http.model.japi.HttpMethod;

/**
 *  Model for the `Allow` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p2-semantics-26#section-7.4.1
 */
public abstract class Allow extends akka.http.model.HttpHeader {
    public abstract Iterable<HttpMethod> getMethods();

    public static Allow create(HttpMethod... methods) {
        return new akka.http.model.headers.Allow(akka.http.model.japi.Util.<HttpMethod, akka.http.model.HttpMethod>convertArray(methods));
    }
}
