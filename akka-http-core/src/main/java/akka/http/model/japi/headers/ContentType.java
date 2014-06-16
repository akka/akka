/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

/**
 *  Model for the `Content-Type` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p2-semantics-26#section-3.1.1.5
 */
public abstract class ContentType extends akka.http.model.HttpHeader {
    public abstract akka.http.model.japi.ContentType contentType();

    public static ContentType create(akka.http.model.japi.ContentType contentType) {
        return new akka.http.model.headers.Content$minusType(((akka.http.model.ContentType) contentType));
    }
}
