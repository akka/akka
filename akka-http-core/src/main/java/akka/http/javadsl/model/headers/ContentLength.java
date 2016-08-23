/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 *  Model for the `Content-Length` header.
 *  Specification: https://tools.ietf.org/html/draft-ietf-httpbis-p1-messaging-26#section-3.3.2
 */
public abstract class ContentLength extends akka.http.scaladsl.model.HttpHeader {
    public abstract long length();
}
