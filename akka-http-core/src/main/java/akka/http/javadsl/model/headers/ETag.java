/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 *  Model for the `ETag` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p4-conditional-26#section-2.3
 */
public abstract class ETag extends akka.http.scaladsl.model.HttpHeader {
    public abstract EntityTag etag();

    public static ETag create(EntityTag etag) {
        return new akka.http.scaladsl.model.headers.ETag(((akka.http.scaladsl.model.headers.EntityTag) etag));
    }
}
