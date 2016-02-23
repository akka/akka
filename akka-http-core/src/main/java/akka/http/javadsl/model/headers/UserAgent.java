/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 *  Model for the `User-Agent` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p2-semantics-26#section-5.5.3
 */
public abstract class UserAgent extends akka.http.scaladsl.model.HttpHeader {
    public abstract Iterable<ProductVersion> getProducts();

    public static UserAgent create(ProductVersion... products) {
        return new akka.http.scaladsl.model.headers.User$minusAgent(akka.http.impl.util.Util.<ProductVersion, akka.http.scaladsl.model.headers.ProductVersion>convertArray(products));
    }
}
