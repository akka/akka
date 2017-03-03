/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.actor

import akka.actor.ActorLogging
import scala.language.postfixOps

import akka.Done
import akka.actor.{ ActorRef, CoordinatedShutdown }
import akka.testkit._
import akka.util._

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

import akka.actor.ActorSystem
import akka.actor.Actor
import akka.actor.Props
import akka.testkit.{ ImplicitSender, TestKit }

class LOLSPEC extends AkkaSpec(Map("akka.loglevel" -> "INFO")) {

  "schedule a one-off task" in {
    val miku = system.actorOf(Props(new Actor {
      def receive = {
        case x =>
          println(s"sender() = ${sender()}")
      }
    }))

    system.eventStream.subscribe(miku, classOf[Object])

    system.eventStream.publish("Hello!")
  }

}

