/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.model;

import akka.http.impl.util.Util;
import akka.http.scaladsl.model.HttpEntity;
import akka.util.ByteString;

/**
 * Represents part of a stream of incoming data for `Transfer-Encoding: chunked` messages.
 */
public abstract class ChunkStreamPart {
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
        return new HttpEntity.Chunk(data, extension);
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
    public static final ChunkStreamPart LAST = HttpEntity.LastChunk$.MODULE$;

    /**
     * Creates a last chunk with extension and headers.
     */
    public static ChunkStreamPart createLast(String extension, Iterable<HttpHeader> trailerHeaders){
        return new HttpEntity.LastChunk(extension, Util.<HttpHeader, akka.http.scaladsl.model.HttpHeader>convertIterable(trailerHeaders));
    }
}
