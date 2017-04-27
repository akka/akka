/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model.http2

import scala.util.control.NoStackTrace

/**
 * Base class for HTTP2 exceptions.
 */
class Http2Exception(msg: String) extends RuntimeException(msg)

/**
 * Exception that will be reported on the request entity stream when the peer closed the stream.
 */
class PeerClosedStreamException(val streamId: Int, val errorCode: String)
  extends Http2Exception(s"Stream with ID [$streamId] was closed by peer with code $errorCode") with NoStackTrace
