/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi;

/**
 * Represents an Http request.
 */
public abstract class HttpRequest implements HttpMessage, HttpMessage.MessageTransformations<HttpRequest> {
    /**
     * Returns the Http method of this request.
     */
    public abstract HttpMethod method();

    /**
     * Returns the Uri of this request.
     */
    public abstract Uri getUri();

    /**
     * Returns the entity of this request.
     */
    public abstract HttpEntityRegular entity();

    /**
     * Returns a copy of this instance with a new method.
     */
    public abstract HttpRequest withMethod(HttpMethod method);

    /**
     * Returns a copy of this instance with a new Uri.
     */
    public abstract HttpRequest withUri(Uri relativeUri);

    /**
     * Returns a copy of this instance with a new Uri.
     */
    public abstract HttpRequest withUri(String path);

    /**
     * Returns a default request to be changed using the `withX` methods.
     */
    public static HttpRequest create() {
        return Accessors$.MODULE$.HttpRequest();
    }
}
