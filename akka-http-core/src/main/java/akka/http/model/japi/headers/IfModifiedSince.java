/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

import akka.http.model.japi.DateTime;

/**
 *  Model for the `If-Modified-Since` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p4-conditional-26#section-3.3
 */
public abstract class IfModifiedSince extends akka.http.model.HttpHeader {
    public abstract DateTime date();

    public static IfModifiedSince create(DateTime date) {
        return new akka.http.model.headers.If$minusModified$minusSince(((akka.http.util.DateTime) date));
    }
}
