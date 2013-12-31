/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package sample.persistence

import akka.actor._
import akka.persistence._

object ReplicatorExample extends App {
  class ExampleProcessor extends Processor {
    override def processorId = "processor-5"

    def receive = {
      case Persistent(payload, sequenceNr) =>
        println(s"processor received ${payload} (sequence nr = ${sequenceNr})")
    }
  }

  class ExampleReplicator extends Replicator {
    private var numReplicated = 0

    override def processorId = "processor-5"
    override def replicatorId = "replicator-5"

    private val destination = context.actorOf(Props[ExampleDestination])
    private val channel = context.actorOf(Channel.props("channel"))

    def receive = {
      case "snap" =>
        saveSnapshot(numReplicated)
      case SnapshotOffer(metadata, snapshot: Int) =>
        numReplicated = snapshot
        println(s"replicator received snapshot offer ${snapshot} (metadata = ${metadata})")
      case Persistent(payload, sequenceNr) =>
        numReplicated += 1
        println(s"replicator received ${payload} (sequence nr = ${sequenceNr}, num replicated = ${numReplicated})")
        channel ! Deliver(Persistent(s"replicated-${payload}"), destination)
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
  val replicator = system.actorOf(Props(classOf[ExampleReplicator]))

  @annotation.tailrec
  def read(line: String): Unit = line match {
    case "exit" | null =>
    case "sync" =>
      replicator ! Replicate(awaitReplication = false)
      read(Console.readLine())
    case "snap" =>
      replicator ! "snap"
      read(Console.readLine())
    case msg =>
      processor ! Persistent(msg)
      read(Console.readLine())
  }

  read(Console.readLine())
  system.shutdown()
}
