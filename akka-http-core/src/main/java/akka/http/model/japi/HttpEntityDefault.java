/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi;

import akka.util.ByteString;
import org.reactivestreams.api.Producer;

/**
 * The default entity type which has a predetermined length and a stream of data bytes.
 */
public abstract class HttpEntityDefault extends HttpEntityRegular {
    public abstract long contentLength();
    public abstract Producer<ByteString> data();
}
