/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

/**
 *  Model for the `Server` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p2-semantics-26#section-7.4.2
 */
public abstract class Server extends akka.http.model.HttpHeader {
    public abstract Iterable<ProductVersion> getProducts();

    public static Server create(ProductVersion... products) {
        return new akka.http.model.headers.Server(akka.http.model.japi.Util.<ProductVersion, akka.http.model.headers.ProductVersion>convertArray(products));
    }
}
