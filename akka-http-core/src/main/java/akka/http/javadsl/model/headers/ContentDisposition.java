/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 *  Model for the `Content-Disposition` header.
 *  Specification: http://tools.ietf.org/html/rfc6266
 */
public abstract class ContentDisposition extends akka.http.scaladsl.model.HttpHeader {
    public abstract ContentDispositionType dispositionType();
    public abstract java.util.Map<String, String> getParams();

    public static ContentDisposition create(ContentDispositionType dispositionType, java.util.Map<String, String> params) {
        return new akka.http.scaladsl.model.headers.Content$minusDisposition(((akka.http.scaladsl.model.headers.ContentDispositionType) dispositionType), akka.http.impl.util.Util.convertMapToScala(params));
    }
}
