/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package sample.persistence

import scala.concurrent.duration._

import akka.actor._
import akka.persistence._

object ViewExample extends App {
  class ExampleProcessor extends Processor {
    override def processorId = "processor-5"

    def receive = {
      case Persistent(payload, sequenceNr) =>
        println(s"processor received ${payload} (sequence nr = ${sequenceNr})")
    }
  }

  class ExampleView extends View {
    private var numReplicated = 0

    override def processorId = "processor-5"
    override def viewId = "view-5"

    private val destination = context.actorOf(Props[ExampleDestination])
    private val channel = context.actorOf(Channel.props("channel"))

    def receive = {
      case "snap" =>
        saveSnapshot(numReplicated)
      case SnapshotOffer(metadata, snapshot: Int) =>
        numReplicated = snapshot
        println(s"view received snapshot offer ${snapshot} (metadata = ${metadata})")
      case Persistent(payload, sequenceNr) =>
        numReplicated += 1
        println(s"view received ${payload} (sequence nr = ${sequenceNr}, num replicated = ${numReplicated})")
        channel ! Deliver(Persistent(s"replicated-${payload}"), destination.path)
    }
  }

  class ExampleDestination extends Actor {
    def receive = {
      case cp @ ConfirmablePersistent(payload, sequenceNr, _) =>
        println(s"destination received ${payload} (sequence nr = ${sequenceNr})")
        cp.confirm()
    }
  }

  val system = ActorSystem("example")

  val processor = system.actorOf(Props(classOf[ExampleProcessor]))
  val view = system.actorOf(Props(classOf[ExampleView]))

  import system.dispatcher

  system.scheduler.schedule(Duration.Zero, 2.seconds, processor, Persistent("scheduled"))
  system.scheduler.schedule(Duration.Zero, 5.seconds, view, "snap")
}
