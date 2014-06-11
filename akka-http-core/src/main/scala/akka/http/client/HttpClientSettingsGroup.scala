/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.client

import akka.http.HttpExt
import akka.actor.{ Props, Actor }

/**
 * INTERNAL API
 */
private[http] class HttpClientSettingsGroup(settings: ClientConnectionSettings,
                                            httpSettings: HttpExt#Settings) extends Actor {
  def receive: Receive = ??? // TODO
}

/**
 * INTERNAL API
 */
private[http] object HttpClientSettingsGroup {
  def props(settings: ClientConnectionSettings, httpSettings: HttpExt#Settings) =
    Props(classOf[HttpClientSettingsGroup], httpSettings) withDispatcher httpSettings.SettingsGroupDispatcher
}