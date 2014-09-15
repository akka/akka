/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.persistence.stream

import org.reactivestreams.Publisher

import akka.actor._
import akka.persistence.PersistentActor
import akka.stream._
import akka.stream.scaladsl._

// FIXME: move this file to akka-sample-persistence-scala once going back to project dependencies

/**
 * This example demonstrates how akka-persistence Views can be used as reactive-stream Publishers. A
 * View-based Publisher is created with PersistentFlow.fromPersistentActor(persistenceId: String).toPublisher().
 * This Publisher produces events as they are written by its corresponding akka-persistence
 * PersistentActor. The PersistentFlow object is an extension to the akka-stream DSL.
 */
object PersistentPublisherExample extends App {
  implicit val system = ActorSystem("example")

  class ExamplePersistentActor(pid: String) extends PersistentActor {
    override def persistenceId = pid
    override def receiveCommand = {
      case cmd: String ⇒ persist(cmd) { event ⇒
        // update state...
      }
    }
    override def receiveRecover = {
      case event: String ⇒ // update state...
    }
  }

  val p1 = system.actorOf(Props(classOf[ExamplePersistentActor], "p1"))
  val p2 = system.actorOf(Props(classOf[ExamplePersistentActor], "p2"))

  implicit val materializer = FlowMaterializer()

  // 1 view-backed publisher and 2 subscribers:
  val publisher1: Publisher[Any] = PersistentFlow.fromPersistentActor("p1").toPublisher()
  Flow(publisher1).foreach(event ⇒ println(s"subscriber-1: $event"))
  Flow(publisher1).foreach(event ⇒ println(s"subscriber-2: $event"))

  // 2 view-backed publishers (merged) and 1 subscriber:
  // This is an example how message/event streams from multiple processors can be merged into a single stream.
  val publisher2: Publisher[Any] = PersistentFlow.fromPersistentActor("p1").toPublisher()
  val merged: Publisher[Any] = PersistentFlow.fromPersistentActor("p2").merge(publisher2).toPublisher()
  Flow(merged).foreach(event ⇒ println(s"subscriber-3: $event"))

  while (true) {
    p1 ! s"a-${System.currentTimeMillis()}"
    p2 ! s"b-${System.currentTimeMillis()}"
    Thread.sleep(500)
  }
}

