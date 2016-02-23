/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 *  Model for the `Content-Type` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p2-semantics-26#section-3.1.1.5
 */
public abstract class ContentType extends akka.http.scaladsl.model.HttpHeader {
    public abstract akka.http.javadsl.model.ContentType contentType();

    public static ContentType create(akka.http.javadsl.model.ContentType contentType) {
        return new akka.http.scaladsl.model.headers.Content$minusType(((akka.http.scaladsl.model.ContentType) contentType));
    }
}
