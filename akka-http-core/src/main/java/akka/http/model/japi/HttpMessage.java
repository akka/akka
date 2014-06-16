/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi;

import akka.japi.Option;
import akka.util.ByteString;

import java.io.File;

/**
 * The base type for an Http message (request or response).
 */
public interface HttpMessage {
    /**
     * Is this instance a request.
     */
    boolean isRequest();

    /**
     * Is this instance a response.
     */
    boolean isResponse();

    /**
     * The protocol of this message.
     */
    HttpProtocol protocol();

    /**
     * An iterable containing the headers of this message.
     */
    Iterable<HttpHeader> getHeaders();

    /**
     * Try to find the first header with the given name (case-insensitive) and return
     * Some(header), otherwise this method returns None.
     */
    Option<HttpHeader> getHeader(String headerName);

    /**
     * Try to find the first header of the given class and return
     * Some(header), otherwise this method returns None.
     */
    <T extends HttpHeader> Option<T> getHeader(Class<T> headerClass);

    /**
     * The entity of this message.
     */
    HttpEntity entity();

    public static interface MessageTransformations<Self> {
        /**
         * Returns a copy of this message with a new protocol.
         */
        Self withProtocol(HttpProtocol protocol);

        /**
         * Returns a copy of this message with the given header added to the list of headers.
         */
        Self addHeader(HttpHeader header);

        /**
         * Returns a copy of this message with the given headers added to the list of headers.
         */
        Self addHeaders(Iterable<HttpHeader> headers);

        /**
         * Returns a copy of this message with all headers of the given name (case-insensitively) removed.
         */
        Self removeHeader(String headerName);

        /**
         * Returns a copy of this message with a new entity.
         */
        Self withEntity(HttpEntity entity);

        /**
         * Returns a copy of this message with a new entity.
         */
        Self withEntity(String string);

        /**
         * Returns a copy of Self message with a new entity.
         */
        Self withEntity(byte[] bytes);

        /**
         * Returns a copy of Self message with a new entity.
         */
        Self withEntity(ByteString bytes);

        /**
         * Returns a copy of Self message with a new entity.
         */
        Self withEntity(ContentType type, String string);

        /**
         * Returns a copy of Self message with a new entity.
         */
        Self withEntity(ContentType type, byte[] bytes);

        /**
         * Returns a copy of Self message with a new entity.
         */
        Self withEntity(ContentType type, ByteString bytes);

        /**
         * Returns a copy of Self message with a new entity.
         */
        Self withEntity(ContentType type, File file);
    }
}
