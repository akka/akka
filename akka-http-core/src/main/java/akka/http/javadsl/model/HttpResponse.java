/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model;

import akka.http.impl.util.JavaAccessors;

/**
 * Represents an Http response.
 */
public abstract class HttpResponse implements HttpMessage, HttpMessage.MessageTransformations<HttpResponse> {
    /**
     * Returns the status-code of this response.
     */
    public abstract StatusCode status();

    /**
     * Returns the entity of this request.
     */
    public abstract ResponseEntity entity();

    /**
     * Returns a copy of this instance with a new status-code.
     */
    public abstract HttpResponse withStatus(StatusCode statusCode);

    /**
     * Returns a copy of this instance with a new status-code.
     */
    public abstract HttpResponse withStatus(int statusCode);

    /**
     * Returns a copy of this instance with a new entity.
     */
    public abstract HttpResponse withEntity(ResponseEntity entity);

    /**
     * Returns a default response to be changed using the `withX` methods.
     */
    public static HttpResponse create() {
        return JavaAccessors.HttpResponse();
    }
}
