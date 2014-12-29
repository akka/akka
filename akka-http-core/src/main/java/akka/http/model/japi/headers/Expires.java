/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

import akka.http.model.japi.DateTime;

/**
 *  Model for the `Expires` header.
 *  Specification: http://tools.ietf.org/html/rfc7234#section-5.3
 */
public abstract class Expires extends akka.http.model.HttpHeader {
    public abstract DateTime date();

    public static Expires create(DateTime date) {
        return new akka.http.model.headers.Expires(((akka.http.util.DateTime) date));
    }
}
