/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

/**
 *  Model for the `ETag` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p4-conditional-26#section-2.3
 */
public abstract class ETag extends akka.http.model.HttpHeader {
    public abstract EntityTag etag();

    public static ETag create(EntityTag etag) {
        return new akka.http.model.headers.ETag(((akka.http.model.headers.EntityTag) etag));
    }
}
