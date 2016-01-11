/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.model.ws

import akka.http.scaladsl.model.HttpResponse

/**
 * Represents the response to a websocket upgrade request. Can either be [[ValidUpgrade]] or [[InvalidUpgradeResponse]].
 */
sealed trait WebsocketUpgradeResponse {
  def response: HttpResponse
}
final case class ValidUpgrade(response: HttpResponse, chosenSubprotocol: Option[String]) extends WebsocketUpgradeResponse
final case class InvalidUpgradeResponse(response: HttpResponse, cause: String) extends WebsocketUpgradeResponse