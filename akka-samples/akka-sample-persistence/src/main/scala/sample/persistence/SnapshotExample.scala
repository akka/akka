/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package sample.persistence

import akka.actor._
import akka.persistence._

object SnapshotExample extends App {
  case class ExampleState(received: List[String] = Nil) {
    def update(s: String) = copy(s :: received)
    override def toString = received.reverse.toString
  }

  class ExampleProcessor extends Processor {
    var state = ExampleState()

    def receive = {
      case Persistent(s, snr)                    => state = state.update(s"${s}-${snr}")
      case SaveSnapshotSuccess(metadata)         => // ...
      case SaveSnapshotFailure(metadata, reason) => // ...
      case SnapshotOffer(_, s: ExampleState) =>
        println("offered state = " + s)
        state = s
      case "print" => println("current state = " + state)
      case "snap"  => saveSnapshot(state)
    }
  }

  val system = ActorSystem("example")
  val processor = system.actorOf(Props(classOf[ExampleProcessor]), "processor-3-scala")

  processor ! Persistent("a")
  processor ! Persistent("b")
  processor ! "snap"
  processor ! Persistent("c")
  processor ! Persistent("d")
  processor ! "print"

  Thread.sleep(1000)
  system.shutdown()
}
