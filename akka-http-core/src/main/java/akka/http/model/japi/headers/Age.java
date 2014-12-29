/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

/**
 *  Model for the `Age` header.
 *  Specification: http://tools.ietf.org/html/rfc7234#section-5.1
 */
public abstract class Age extends akka.http.model.HttpHeader {
    public abstract long deltaSeconds();

    public static Age create(long deltaSeconds) {
        return new akka.http.model.headers.Age(deltaSeconds);
    }
}
