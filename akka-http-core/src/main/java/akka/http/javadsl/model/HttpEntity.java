/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.model;

import akka.http.impl.util.Util;
import akka.http.scaladsl.model.HttpEntity$;
import akka.japi.Option;
import akka.stream.Materializer;
import akka.stream.javadsl.Source;
import akka.util.ByteString;
import scala.concurrent.Future;

/**
 * Represents the entity of an Http message. An entity consists of the content-type of the data
 * and the actual data itself. Some subtypes of HttpEntity also define the content-length of the
 * data.
 *
 * An HttpEntity can be of several kinds:
 *
 *  - HttpEntity.Empty: the statically known empty entity
 *  - HttpEntity.Strict: an entity containing already evaluated ByteString data
 *  - HttpEntity.Default: the default entity which has a known length and which contains
 *                       a stream of ByteStrings.
 *  - HttpEntity.Chunked: represents an entity that is delivered using `Transfer-Encoding: chunked`
 *  - HttpEntity.CloseDelimited: an entity which doesn't have a fixed length but which is delimited by
 *                              closing the connection.
 *  - HttpEntity.IndefiniteLength: an entity which doesn't have a fixed length which can be used to construct BodyParts
 *                                with indefinite length
 *
 *  Marker-interfaces denote which subclasses can be used in which context:
 *  - RequestEntity: an entity type that can be used in an HttpRequest
 *  - ResponseEntity: an entity type that can be used in an HttpResponse
 *  - BodyPartEntity: an entity type that can be used in a BodyPart
 *  - UniversalEntity: an entity type that can be used in every context
 *
 * Use the static constructors in HttpEntities to construct instances.
 */
public interface HttpEntity {
    /**
     * Returns the content-type of this entity
     */
    ContentType getContentType();

    /**
     * The empty entity.
     */
    HttpEntity.Strict EMPTY = HttpEntity$.MODULE$.Empty();

    /**
     * Returns if this entity is known to be empty. Open-ended entity types like
     * HttpEntityChunked and HttpCloseDelimited will always return false here.
     */
    boolean isKnownEmpty();

    /**
     * Returns if this entity is a subtype of HttpEntityChunked.
     */
    boolean isChunked();

    /**
     * Returns if this entity is a subtype of HttpEntityDefault.
     */
    boolean isDefault();

    /**
     * Returns if this entity is a subtype of HttpEntityCloseDelimited.
     */
    boolean isCloseDelimited();

    /**
     * Returns if this entity is a subtype of HttpEntityIndefiniteLength.
     */
    boolean isIndefiniteLength();

    /**
     * Returns Some(contentLength) if the length is defined and none otherwise.
     */
    Option<Long> getContentLengthOption();

    /**
     * Returns a stream of data bytes this entity consists of.
     */
    Source<ByteString, Object> getDataBytes();

    /**
     * Returns a future of a strict entity that contains the same data as this entity
     * which is only completed when the complete entity has been collected. As the
     * duration of receiving the complete entity cannot be predicted, a timeout needs to
     * be specified to guard the process against running and keeping resources infinitely.
     *
     * Use getDataBytes and stream processing instead if the expected data is big or
     * is likely to take a long time.
     */
    Future<HttpEntity.Strict> toStrict(long timeoutMillis, Materializer materializer);

    /**
     * The entity type which consists of a predefined fixed ByteString of data.
     */
    interface Strict extends UniversalEntity {
        ByteString getData();
    }

    /**
     * The default entity type which has a predetermined length and a stream of data bytes.
     */
    interface Default extends UniversalEntity {
        long getContentLength();
    }

    /**
     * Represents an entity without a predetermined content-length. Its length is implicitly
     * determined by closing the underlying connection. Therefore, this entity type is only
     * available for Http responses.
     */
    interface CloseDelimited extends ResponseEntity {
    }

    /**
     * Represents an entity transferred using `Transfer-Encoding: chunked`. It consists of a
     * stream of {@link ChunkStreamPart}.
     */
    interface Chunked extends RequestEntity, ResponseEntity {
        Source<ChunkStreamPart, Object> getChunks();
    }

    /**
     * Represents an entity without a predetermined content-length to use in a BodyParts.
     */
    interface IndefiniteLength extends BodyPartEntity {
    }

    /**
     * A part of a stream of incoming data for `Transfer-Encoding: chunked` messages.
     */
    abstract class ChunkStreamPart {
        /**
         * Returns the byte data of this chunk. Will be non-empty for every regular
         * chunk. Will be empty for the last chunk.
         */
        public abstract ByteString data();

        /**
         * Returns extensions data for this chunk.
         */
        public abstract String extension();

        /**
         * Returns if this is the last chunk
         */
        public abstract boolean isLastChunk();

        /**
         * If this is the last chunk, this will return an Iterable of the trailer headers. Otherwise,
         * it will be empty.
         */
        public abstract Iterable<HttpHeader> getTrailerHeaders();

        /**
         * Creates a chunk from data and extension.
         */
        public static ChunkStreamPart create(ByteString data, String extension) {
            return new akka.http.scaladsl.model.HttpEntity.Chunk(data, extension);
        }

        /**
         * Creates a chunk from data with an empty extension.
         */
        public static ChunkStreamPart create(ByteString data) {
            return create(data, "");
        }

        /**
         * The default last ChunkStreamPart that has no extension and no trailer headers.
         */
        public static final ChunkStreamPart LAST = akka.http.scaladsl.model.HttpEntity.LastChunk$.MODULE$;

        /**
         * Creates a last chunk with extension and headers.
         */
        public static ChunkStreamPart createLast(String extension, Iterable<HttpHeader> trailerHeaders){
            return new akka.http.scaladsl.model.HttpEntity.LastChunk(extension, Util.<HttpHeader, akka.http.scaladsl.model.HttpHeader>convertIterable(trailerHeaders));
        }
    }
}
