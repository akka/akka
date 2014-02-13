/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package sample.persistence

import akka.actor._
import akka.persistence._

object ProcessorChannelExample extends App {
  class ExampleProcessor extends Processor {
    val channel = context.actorOf(Channel.props, "channel")
    val destination = context.actorOf(Props[ExampleDestination])

    def receive = {
      case p @ Persistent(payload, _) =>
        println(s"processed ${payload}")
        channel ! Deliver(p.withPayload(s"processed ${payload}"), destination.path)
      case reply: String =>
        println(s"reply = ${reply}")
    }
  }

  class ExampleDestination extends Actor {
    def receive = {
      case p @ ConfirmablePersistent(payload, snr, _) =>
        println(s"received ${payload}")
        sender ! s"re: ${payload} (${snr})"
        p.confirm()
    }
  }

  val system = ActorSystem("example")
  val processor = system.actorOf(Props(classOf[ExampleProcessor]), "processor-1")

  processor ! Persistent("a")
  processor ! Persistent("b")

  Thread.sleep(1000)
  system.shutdown()
}
