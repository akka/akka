/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi;

import org.reactivestreams.api.Producer;

/**
 * Represents an entity transferred using `Transfer-Encoding: chunked`. It consists of a
 * stream of {@link ChunkStreamPart}.
 */
public abstract class HttpEntityChunked extends HttpEntityRegular {
    public abstract Producer<ChunkStreamPart> getChunks();
}
