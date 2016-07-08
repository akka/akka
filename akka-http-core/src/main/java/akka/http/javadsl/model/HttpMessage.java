/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model;

import akka.Done;
import akka.stream.Materializer;
import akka.http.javadsl.model.headers.HttpCredentials;
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
     * Discards the entities data bytes by running the {@code dataBytes} Source contained by the {@code entity} 
     * of this HTTP message.
     * 
     * Note: It is crucial that entities are either discarded, or consumed by running the underlying [[Source]]
     * as otherwise the lack of consuming of the data will trigger back-pressure to the underlying TCP connection
     * (as designed), however possibly leading to an idle-timeout that will close the connection, instead of 
     * just having ignored the data.
     *  
     * Warning: It is not allowed to discard and/or consume the the {@code entity.dataBytes} more than once
     * as the stream is directly attached to the "live" incoming data source from the underlying TCP connection.
     * Allowing it to be consumable twice would require buffering the incoming data, thus defeating the purpose
     * of its streaming nature. If the dataBytes source is materialized a second time, it will fail with an
     * "stream can cannot be materialized more than once" exception.
     * 
     * In future versions, more automatic ways to warn or resolve these situations may be introduced, see issue #18716.
     */
    DiscardedEntity discardEntityBytes(Materializer materializer);

    /**
     * Represents the the currently being-drained HTTP Entity which triggers completion of the contained
     * Future once the entity has been drained for the given HttpMessage completely.
     */
    interface DiscardedEntity {
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

    interface MessageTransformations<Self> {
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
         * Returns a copy of this message with the given http credential header added to the list of headers.
         */
        Self addCredentials(HttpCredentials credentials);

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
