/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model;

import akka.Done;
import akka.stream.Materializer;
import akka.util.ByteString;
import scala.concurrent.Future;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * The base type for an Http message (request or response).
 *
 * INTERNAL API: this trait will be changed in binary-incompatible ways for classes that are derived from it!
 * Do not implement this interface outside the Akka code base!
 *
 * Binary compatibility is only maintained for callers of this trait’s interface.
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
    Optional<HttpHeader> getHeader(String headerName);

    /**
     * Try to find the first header of the given class and return
     * Some(header), otherwise this method returns None.
     */
    <T extends HttpHeader> Optional<T> getHeader(Class<T> headerClass);

    /**
     * The entity of this message.
     */
    ResponseEntity entity();

    /**
     *  Drains entity stream of this message
     */
    DiscardedEntity discardEntityBytes(Materializer materializer);

    /**
     * Represents the the currently being-drained HTTP Entity which triggers completion of the contained
     * Future once the entity has been drained for the given HttpMessage completely.
     */
    public interface DiscardedEntity {
        /**
         * This future completes successfully once the underlying entity stream has been
         * successfully drained (and fails otherwise).
         */
        Future<Done> future();

        /**
         * This future completes successfully once the underlying entity stream has been
         * successfully drained (and fails otherwise).
         */
        CompletionStage<Done> completionStage();
    }

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
        Self withEntity(ContentType.NonBinary type, String string);

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
         *
         * @deprecated Will be removed in Akka 3.x, use {@link #withEntity(ContentType, Path)} instead.
         */
        @Deprecated
        Self withEntity(ContentType type, File file);

        /**
         * Returns a copy of Self message with a new entity.
         */
        Self withEntity(ContentType type, Path file);

        /**
         * Returns a copy of Self message with a new entity.
         */
        Self withEntity(RequestEntity entity);
    }
}
