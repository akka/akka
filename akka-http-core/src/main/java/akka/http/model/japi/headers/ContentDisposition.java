/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

/**
 *  Model for the `Content-Disposition` header.
 *  Specification: http://tools.ietf.org/html/rfc6266
 */
public abstract class ContentDisposition extends akka.http.model.HttpHeader {
    public abstract ContentDispositionType dispositionType();
    public abstract java.util.Map<String, String> getParams();

    public static ContentDisposition create(ContentDispositionType dispositionType, java.util.Map<String, String> params) {
        return new akka.http.model.headers.Content$minusDisposition(((akka.http.model.headers.ContentDispositionType) dispositionType), akka.http.model.japi.Util.convertMapToScala(params));
    }
}
