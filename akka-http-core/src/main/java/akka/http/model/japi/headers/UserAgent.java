/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

/**
 *  Model for the `User-Agent` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p2-semantics-26#section-5.5.3
 */
public abstract class UserAgent extends akka.http.model.HttpHeader {
    public abstract Iterable<ProductVersion> getProducts();

    public static UserAgent create(ProductVersion... products) {
        return new akka.http.model.headers.User$minusAgent(akka.http.model.japi.Util.<ProductVersion, akka.http.model.headers.ProductVersion>convertArray(products));
    }
}
