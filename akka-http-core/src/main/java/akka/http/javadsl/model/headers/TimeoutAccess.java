/**
 * Copyright (C) 2009-2016 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.model.headers;

/**
 * Model for the synthetic `Timeout-Access` header.
 */
public abstract class TimeoutAccess extends akka.http.scaladsl.model.HttpHeader {
    public abstract akka.http.javadsl.TimeoutAccess timeoutAccess();

    public static TimeoutAccess create(akka.http.javadsl.TimeoutAccess timeoutAccess) {
        return new akka.http.scaladsl.model.headers.Timeout$minusAccess((akka.http.scaladsl.TimeoutAccess) timeoutAccess);
    }
}
