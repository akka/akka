/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import akka.http.javadsl.model.DateTime;

/**
 *  Model for the `Expires` header.
 *  Specification: http://tools.ietf.org/html/rfc7234#section-5.3
 */
public abstract class Expires extends akka.http.scaladsl.model.HttpHeader {
    public abstract DateTime date();

    public static Expires create(DateTime date) {
        return new akka.http.scaladsl.model.headers.Expires(((akka.http.scaladsl.model.DateTime) date));
    }
}
