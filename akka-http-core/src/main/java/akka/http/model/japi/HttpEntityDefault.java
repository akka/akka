/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi;

import akka.stream.scaladsl2.FlowWithSource;
import akka.util.ByteString;

/**
 * The default entity type which has a predetermined length and a stream of data bytes.
 */
public abstract class HttpEntityDefault implements BodyPartEntity, RequestEntity, ResponseEntity {
    public abstract long contentLength();
    public abstract FlowWithSource<?, ByteString> data();
}
