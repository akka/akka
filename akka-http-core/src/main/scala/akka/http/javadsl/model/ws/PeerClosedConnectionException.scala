/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.ws

/**
 * A PeerClosedConnectionException will be reported to the WebSocket handler if the peer has closed the connection.
 * `closeCode` and `closeReason` contain close messages as reported by the peer.
 */
trait PeerClosedConnectionException extends RuntimeException {
  def closeCode: Int
  def closeReason: String
}
