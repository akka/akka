/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 *  Model for the `Link` header.
 *  Specification: http://tools.ietf.org/html/rfc5988#section-5
 */
public abstract class Link extends akka.http.scaladsl.model.HttpHeader {
    public abstract Iterable<LinkValue> getValues();

    public static Link create(LinkValue... values) {
        return new akka.http.scaladsl.model.headers.Link(akka.http.impl.util.Util.<LinkValue, akka.http.scaladsl.model.headers.LinkValue>convertArray(values));
    }
}
