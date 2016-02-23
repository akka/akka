/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model.ws

import akka.http.javadsl

/**
 * A PeerClosedConnectionException will be reported to the WebSocket handler if the peer has closed the connection.
 * `closeCode` and `closeReason` contain close messages as reported by the peer.
 */
class PeerClosedConnectionException(val closeCode: Int, val closeReason: String)
  extends RuntimeException(s"Peer closed connection with code $closeCode '$closeReason'") with javadsl.model.ws.PeerClosedConnectionException
