/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 *  Model for the `Age` header.
 *  Specification: http://tools.ietf.org/html/rfc7234#section-5.1
 */
public abstract class Age extends akka.http.scaladsl.model.HttpHeader {
    public abstract long deltaSeconds();

    public static Age create(long deltaSeconds) {
        return new akka.http.scaladsl.model.headers.Age(deltaSeconds);
    }
}
