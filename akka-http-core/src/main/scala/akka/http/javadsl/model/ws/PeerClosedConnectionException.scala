/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.model.ws

/**
 * A PeerClosedConnectionException will be reported to the Websocket handler if the peer has closed the connection.
 * `closeCode` and `closeReason` contain close messages as reported by the peer.
 */
trait PeerClosedConnectionException extends RuntimeException {
  def closeCode: Int
  def closeReason: String
}
