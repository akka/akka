/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi;

/**
 * Represents an HTTP request method. See {@link HttpMethods} for a set of predefined methods
 * and static constructors to build and register custom ones.
 */
public abstract class HttpMethod {
    /**
     * Returns the name of the method.
     */
    public abstract String value();

    /**
     * Returns if this method is "safe" as defined in
     * http://tools.ietf.org/html/draft-ietf-httpbis-p2-semantics-26#section-4.2.1
     */
    public abstract boolean isSafe();

    /**
     * Returns if this method is "idempotent" as defined in
     * http://tools.ietf.org/html/draft-ietf-httpbis-p2-semantics-26#section-4.2.2
     */
    public abstract boolean isIdempotent();

    /**
     * Returns if requests with this method may contain an entity.
     */
    public abstract boolean isEntityAccepted();
}
