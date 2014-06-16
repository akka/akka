/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi;

import akka.http.model.HttpEntity$;
import akka.stream.FlowMaterializer;
import akka.util.ByteString;
import org.reactivestreams.api.Producer;

import java.io.File;

/**
 * Represents the entity of an Http message. An entity consists of the content-type of the data
 * and the actual data itself. Some subtypes of HttpEntity also define the content-length of the
 * data.
 *
 * An HttpEntity can be of several kinds:
 *
 *  - HttpEntity.Empty: the statically known empty entity
 *  - HttpEntityDefault: the default entity which has a known length and which contains
 *                       a stream of ByteStrings.
 *  - HttpEntityChunked: represents an entity that is delivered using `Transfer-Encoding: chunked`
 *  - HttpEntityCloseDelimited: the entity which doesn't have a fixed length but which is delimited by
 *                              closing the connection.
 *
 *  All entity subtypes but HttpEntityCloseDelimited are subtypes of {@link HttpEntityRegular} which
 *  means they can be used in Http request that disallow close-delimited transfer of the entity.
 */
public abstract class HttpEntity {
    /**
     * Returns the content-type of this entity
     */
    public abstract ContentType contentType();

    /**
     * The empty entity.
     */
    public static final HttpEntityStrict EMPTY = HttpEntity$.MODULE$.Empty();

    /**
     * Returns if this entity is known to be empty. Open-ended entity types like
     * HttpEntityChunked and HttpCloseDelimited will always return false here.
     */
    public abstract boolean isKnownEmpty();

    /**
     * Returns if this entity is a subtype of HttpEntityRegular.
     */
    public abstract boolean isRegular();

    /**
     * Returns if this entity is a subtype of HttpEntityChunked.
     */
    public abstract boolean isChunked();

    /**
     * Returns if this entity is a subtype of HttpEntityDefault.
     */
    public abstract boolean isDefault();

    /**
     * Returns if this entity is a subtype of HttpEntityCloseDelimited.
     */
    public abstract boolean isCloseDelimited();

    /**
     * Returns a stream of data bytes this entity consists of.
     */
    public abstract Producer<ByteString> getDataBytes(FlowMaterializer materializer);

    public static HttpEntityStrict create(String string) {
        return HttpEntity$.MODULE$.apply(string);
    }
    public static HttpEntityStrict create(byte[] bytes) {
        return HttpEntity$.MODULE$.apply(bytes);
    }
    public static HttpEntityStrict create(ByteString bytes) {
        return HttpEntity$.MODULE$.apply(bytes);
    }
    public static HttpEntityStrict create(ContentType contentType, String string) {
        return HttpEntity$.MODULE$.apply((akka.http.model.ContentType) contentType, string);
    }
    public static HttpEntityStrict create(ContentType contentType, byte[] bytes) {
        return HttpEntity$.MODULE$.apply((akka.http.model.ContentType) contentType, bytes);
    }
    public static HttpEntityStrict create(ContentType contentType, ByteString bytes) {
        return HttpEntity$.MODULE$.apply((akka.http.model.ContentType) contentType, bytes);
    }
    public static HttpEntityRegular create(ContentType contentType, File file) {
        return (HttpEntityRegular) HttpEntity$.MODULE$.apply((akka.http.model.ContentType) contentType, file);
    }
    public static HttpEntityDefault create(ContentType contentType, long contentLength, Producer<ByteString> data) {
        return new akka.http.model.HttpEntity.Default((akka.http.model.ContentType) contentType, contentLength, data);
    }
    public static HttpEntityCloseDelimited createCloseDelimited(ContentType contentType, Producer<ByteString> data) {
        return new akka.http.model.HttpEntity.CloseDelimited((akka.http.model.ContentType) contentType, data);
    }
    public static HttpEntityChunked createChunked(ContentType contentType, Producer<ChunkStreamPart> chunks) {
        return new akka.http.model.HttpEntity.Chunked(
                (akka.http.model.ContentType) contentType,
                Util.<ChunkStreamPart, akka.http.model.HttpEntity.ChunkStreamPart>upcastProducer(chunks));
    }
    public static HttpEntityChunked createChunked(ContentType contentType, Producer<ByteString> data, FlowMaterializer materializer) {
        return akka.http.model.HttpEntity.Chunked$.MODULE$.apply(
                (akka.http.model.ContentType) contentType,
                data, materializer);
    }
}
