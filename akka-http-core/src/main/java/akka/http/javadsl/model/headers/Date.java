/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import akka.http.javadsl.model.DateTime;

/**
 *  Model for the `Date` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p2-semantics-26#section-7.1.1.2
 */
public abstract class Date extends akka.http.scaladsl.model.HttpHeader {
    public abstract DateTime date();

    public static Date create(DateTime date) {
        return new akka.http.scaladsl.model.headers.Date(((akka.http.scaladsl.model.DateTime) date));
    }
}
