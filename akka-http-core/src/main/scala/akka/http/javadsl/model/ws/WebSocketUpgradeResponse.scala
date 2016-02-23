/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.ws

import java.util.Optional

import akka.http.javadsl.model.HttpResponse
import akka.http.scaladsl
import akka.http.scaladsl.model.ws.{ InvalidUpgradeResponse, ValidUpgrade }

/**
 * Represents an upgrade response for a WebSocket upgrade request. Can either be valid, in which
 * case the `chosenSubprotocol` method is valid, or if invalid, the `invalidationReason` method
 * can be used to find out why the upgrade failed.
 */
trait WebSocketUpgradeResponse {
  def isValid: Boolean

  /**
   * Returns the response object as received from the server for further inspection.
   */
  def response: HttpResponse

  /**
   * If valid, returns `Some(subprotocol)` (if any was requested), or `None` if none was
   * chosen or offered.
   */
  def chosenSubprotocol: Optional[String]

  /**
   * If invalid, the reason why the server's upgrade response could not be accepted.
   */
  def invalidationReason: String
}

object WebSocketUpgradeResponse {
  import akka.http.impl.util.JavaMapping.Implicits._
  def adapt(scalaResponse: scaladsl.model.ws.WebSocketUpgradeResponse): WebSocketUpgradeResponse =
    scalaResponse match {
      case ValidUpgrade(resp, chosen) ⇒
        new WebSocketUpgradeResponse {
          def isValid: Boolean = true
          def response: HttpResponse = resp
          def chosenSubprotocol: Optional[String] = chosen.asJava
          def invalidationReason: String =
            throw new UnsupportedOperationException("invalidationReason must not be called for valid response")
        }
      case InvalidUpgradeResponse(resp, cause) ⇒
        new WebSocketUpgradeResponse {
          def isValid: Boolean = false
          def response: HttpResponse = resp
          def chosenSubprotocol: Optional[String] = throw new UnsupportedOperationException("chosenSubprotocol must not be called for valid response")
          def invalidationReason: String = cause
        }
    }

}