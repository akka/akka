/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import akka.http.javadsl.model.DateTime;

/**
 *  Model for the `If-Modified-Since` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p4-conditional-26#section-3.3
 */
public abstract class IfModifiedSince extends akka.http.scaladsl.model.HttpHeader {
    public abstract DateTime date();

    public static IfModifiedSince create(DateTime date) {
        return new akka.http.scaladsl.model.headers.If$minusModified$minusSince(((akka.http.scaladsl.model.DateTime) date));
    }
}
