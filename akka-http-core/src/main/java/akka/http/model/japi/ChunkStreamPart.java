/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi;

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
}
