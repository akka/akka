/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server

import akka.actor.{ ActorRef, Actor, ActorLogging }
import akka.http.{ Http, HttpExt }

private[http] class HttpListener(bindCommander: ActorRef,
                                 bind: Http.Bind,
                                 httpSettings: HttpExt#Settings) extends Actor with ActorLogging {

  def receive: Receive = ??? // TODO
}
