/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

/**
 *  Model for the `Link` header.
 *  Specification: http://tools.ietf.org/html/rfc5988#section-5
 */
public abstract class Link extends akka.http.model.HttpHeader {
    public abstract Iterable<LinkValue> getValues();

    public static Link create(LinkValue... values) {
        return new akka.http.model.headers.Link(akka.http.model.japi.Util.<LinkValue, akka.http.model.headers.LinkValue>convertArray(values));
    }
}
