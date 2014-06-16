/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

import akka.http.model.japi.DateTime;

/**
 *  Model for the `Last-Modified` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p4-conditional-26#section-2.2
 */
public abstract class LastModified extends akka.http.model.HttpHeader {
    public abstract DateTime date();

    public static LastModified create(DateTime date) {
        return new akka.http.model.headers.Last$minusModified(((akka.http.util.DateTime) date));
    }
}
