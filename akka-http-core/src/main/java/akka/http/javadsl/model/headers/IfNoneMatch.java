/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 *  Model for the `If-None-Match` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p4-conditional-26#section-3.2
 */
public abstract class IfNoneMatch extends akka.http.scaladsl.model.HttpHeader {
    public abstract EntityTagRange m();

    public static IfNoneMatch create(EntityTagRange m) {
        return new akka.http.scaladsl.model.headers.If$minusNone$minusMatch(((akka.http.scaladsl.model.headers.EntityTagRange) m));
    }
}
