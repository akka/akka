/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.model;

import akka.stream.javadsl.Source;

/**
 * Represents an entity transferred using `Transfer-Encoding: chunked`. It consists of a
 * stream of {@link ChunkStreamPart}.
 */
public abstract class HttpEntityChunked implements RequestEntity, ResponseEntity {
    public abstract Source<ChunkStreamPart, ?> getChunks();
}
