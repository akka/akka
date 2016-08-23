/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model;

/**
 * Represents an HTTP request method. See {@link HttpMethods} for a set of predefined methods
 * and static constructors to create custom ones.
 *
 * @see HttpMethods for convenience access to often used values.
 */
public abstract class HttpMethod {

    /**
     * Returns the name of the method, always equal to [[value]].
     */
    public final String name() {
        return value();
    }
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

    /**
     * Returns the entity acceptance level for this method.
     * @deprecated Use {@link #getRequestEntityAcceptance} instead, which returns {@link akka.http.javadsl.model.RequestEntityAcceptance}.
     */
    @Deprecated
    public abstract akka.http.scaladsl.model.RequestEntityAcceptance requestEntityAcceptance();

    /**
     * Java API: Returns the entity acceptance level for this method.
     */
    // TODO: Rename it to requestEntityAcceptance() in Akka 3.0
    public abstract akka.http.javadsl.model.RequestEntityAcceptance getRequestEntityAcceptance();
}
