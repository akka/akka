/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package sample.persistence

import akka.actor._
import akka.persistence._

object ProcessorFailureExample extends App {
  class ExampleProcessor extends Processor {
    var received: List[String] = Nil // state

    def receive = {
      case "print"                        => println(s"received ${received.reverse}")
      case "boom"                         => throw new Exception("boom")
      case Persistent("boom", _)          => throw new Exception("boom")
      case Persistent(payload: String, _) => received = payload :: received
    }

    override def preRestart(reason: Throwable, message: Option[Any]) {
      message match {
        case Some(p: Persistent) if !recoveryRunning => deleteMessage(p.sequenceNr) // mark failing message as deleted
        case _                                       => // ignore
      }
      super.preRestart(reason, message)
    }
  }

  val system = ActorSystem("example")
  val processor = system.actorOf(Props(classOf[ExampleProcessor]), "processor-2")

  processor ! Persistent("a")
  processor ! "print"
  processor ! "boom" // restart and recovery
  processor ! "print"
  processor ! Persistent("b")
  processor ! "print"
  processor ! Persistent("boom") // restart, recovery and deletion of message from journal
  processor ! "print"
  processor ! Persistent("c")
  processor ! "print"

  // Will print in a first run (i.e. with empty journal):

  // received List(a)
  // received List(a, b)
  // received List(a, b, c)

  // Will print in a second run:

  // received List(a, b, c, a)
  // received List(a, b, c, a, b)
  // received List(a, b, c, a, b, c)

  // etc ...

  Thread.sleep(1000)
  system.shutdown()
}
