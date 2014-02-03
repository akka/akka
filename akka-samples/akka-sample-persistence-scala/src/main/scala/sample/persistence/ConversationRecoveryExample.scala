/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package sample.persistence

import akka.actor._
import akka.persistence._

object ConversationRecoveryExample extends App {
  case object Ping
  case object Pong

  class Ping extends Processor {
    val pongChannel = context.actorOf(Channel.props, "pongChannel")
    var counter = 0

    def receive = {
      case m @ ConfirmablePersistent(Ping, _, _) =>
        counter += 1
        println(s"received ping ${counter} times ...")
        m.confirm()
        if (!recoveryRunning) Thread.sleep(1000)
        pongChannel ! Deliver(m.withPayload(Pong), sender.path)
      case "init" if (counter == 0) =>
        pongChannel ! Deliver(Persistent(Pong), sender.path)
    }
  }

  class Pong extends Processor {
    val pingChannel = context.actorOf(Channel.props, "pingChannel")
    var counter = 0

    def receive = {
      case m @ ConfirmablePersistent(Pong, _, _) =>
        counter += 1
        println(s"received pong ${counter} times ...")
        m.confirm()
        if (!recoveryRunning) Thread.sleep(1000)
        pingChannel ! Deliver(m.withPayload(Ping), sender.path)
    }
  }

  val system = ActorSystem("example")

  val ping = system.actorOf(Props(classOf[Ping]), "ping")
  val pong = system.actorOf(Props(classOf[Pong]), "pong")

  ping tell ("init", pong)
}
