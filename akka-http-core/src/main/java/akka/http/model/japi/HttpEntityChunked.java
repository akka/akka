/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi;

import org.reactivestreams.Publisher;

/**
 * Represents an entity transferred using `Transfer-Encoding: chunked`. It consists of a
 * stream of {@link ChunkStreamPart}.
 */
public abstract class HttpEntityChunked implements RequestEntity, ResponseEntity {
    public abstract Publisher<ChunkStreamPart> getChunks();
}
