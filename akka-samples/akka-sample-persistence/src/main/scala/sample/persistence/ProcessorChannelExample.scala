/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package sample.persistence

import akka.actor._
import akka.pattern.ask
import akka.persistence._
import akka.util.Timeout

object ProcessorChannelExample extends App {
  class ExampleProcessor extends Processor {
    val channel = context.actorOf(Channel.props, "channel")
    val destination = context.actorOf(Props[ExampleDestination])
    var received: List[Persistent] = Nil

    def receive = {
      case p @ Persistent(payload, _) ⇒
        println(s"processed ${payload}")
        channel forward Deliver(p.withPayload(s"processed ${payload}"), destination)
    }
  }

  class ExampleDestination extends Actor {
    def receive = {
      case p @ ConfirmablePersistent(payload, snr) ⇒
        println(s"received ${payload}")
        sender ! s"re: ${payload} (${snr})"
        p.confirm()
    }
  }

  val system = ActorSystem("example")
  val processor = system.actorOf(Props(classOf[ExampleProcessor]), "processor-1")

  implicit val timeout = Timeout(3000)
  import system.dispatcher

  processor ? Persistent("a") onSuccess { case reply ⇒ println(s"reply = ${reply}") }
  processor ? Persistent("b") onSuccess { case reply ⇒ println(s"reply = ${reply}") }

  Thread.sleep(1000)
  system.shutdown()
}
