/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 *  Model for the `Server` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p2-semantics-26#section-7.4.2
 */
public abstract class Server extends akka.http.scaladsl.model.HttpHeader {
    public abstract Iterable<ProductVersion> getProducts();

    public static Server create(ProductVersion... products) {
        return new akka.http.scaladsl.model.headers.Server(akka.http.impl.util.Util.<ProductVersion, akka.http.scaladsl.model.headers.ProductVersion>convertArray(products));
    }
}
