/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package sample.hello

import akka.actor._
import akka.http._

import java.text.DateFormat
import java.util.Date

class HelloEndpoint extends Actor with Endpoint {
  self.dispatcher = Endpoint.Dispatcher

  lazy val hello = Actor.actorOf(
    new Actor {
      def time = DateFormat.getTimeInstance.format(new Date)
      def receive = {
        case get: Get => get OK "Hello at " + time
      }
    })

  def hook: Endpoint.Hook = { case _ => hello }

  override def preStart = Actor.registry.actorFor(MistSettings.RootActorID).get ! Endpoint.Attach(hook)

  def receive = handleHttpRequest
}
