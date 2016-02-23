/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import akka.http.javadsl.model.DateTime;

/**
 *  Model for the `Last-Modified` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p4-conditional-26#section-2.2
 */
public abstract class LastModified extends akka.http.scaladsl.model.HttpHeader {
    public abstract DateTime date();

    public static LastModified create(DateTime date) {
        return new akka.http.scaladsl.model.headers.Last$minusModified(((akka.http.scaladsl.model.DateTime) date));
    }
}
