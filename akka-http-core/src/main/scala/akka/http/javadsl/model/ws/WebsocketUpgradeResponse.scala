/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.model.ws

import akka.http.javadsl.model.HttpResponse
import akka.http.scaladsl
import akka.http.scaladsl.model.ws.{ InvalidUpgradeResponse, ValidUpgrade }
import akka.japi.Option

/**
 * Represents an upgrade response for a Websocket upgrade request. Can either be valid, in which
 * case the `chosenSubprotocol` method is valid, or if invalid, the `invalidationReason` method
 * can be used to find out why the upgrade failed.
 */
trait WebsocketUpgradeResponse {
  def isValid: Boolean

  /**
   * Returns the response object as received from the server for further inspection.
   */
  def response: HttpResponse

  /**
   * If valid, returns `Some(subprotocol)` (if any was requested), or `None` if none was
   * chosen or offered.
   */
  def chosenSubprotocol: Option[String]

  /**
   * If invalid, the reason why the server's upgrade response could not be accepted.
   */
  def invalidationReason: String
}

object WebsocketUpgradeResponse {
  import akka.http.impl.util.JavaMapping.Implicits._
  def adapt(scalaResponse: scaladsl.model.ws.WebsocketUpgradeResponse): WebsocketUpgradeResponse =
    scalaResponse match {
      case ValidUpgrade(response, chosen) ⇒
        new WebsocketUpgradeResponse {
          def isValid: Boolean = true
          def response: HttpResponse = response
          def chosenSubprotocol: Option[String] = chosen.asJava
          def invalidationReason: String =
            throw new UnsupportedOperationException("invalidationReason must not be called for valid response")
        }
      case InvalidUpgradeResponse(response, cause) ⇒
        new WebsocketUpgradeResponse {
          def isValid: Boolean = false
          def response: HttpResponse = response
          def chosenSubprotocol: Option[String] = throw new UnsupportedOperationException("chosenSubprotocol must not be called for valid response")
          def invalidationReason: String = cause
        }
    }

}