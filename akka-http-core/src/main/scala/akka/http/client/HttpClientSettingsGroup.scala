/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.client

import akka.http.HttpExt
import akka.actor.Actor

private[http] class HttpClientSettingsGroup(settings: ClientConnectionSettings,
                                            httpSettings: HttpExt#Settings) extends Actor {
  def receive: Receive = ??? // TODO
}
