/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package sample.persistence

import org.reactivestreams.Publisher

import akka.actor._
import akka.persistence.{ Persistent, Processor }
import akka.persistence.stream.PersistentFlow
import akka.stream._
import akka.stream.scaladsl._

/**
 * This example demonstrates how akka-persistence Views can be used as reactive-stream Producers. A
 * View-based Producer is created with PersistentFlow.fromProcessor(processorId: String).toProducer().
 * This Producer produces Persistent messages as they are written by its corresponding akka-persistence
 * Processor. The PersistentFlow object is an extension to the akka-stream DSL.
 */
object StreamExample extends App {
  implicit val system = ActorSystem("example")

  class ExampleProcessor(pid: String) extends Processor {
    override def processorId = pid
    def receive = {
      case Persistent(payload, _) =>
    }
  }

  val p1 = system.actorOf(Props(classOf[ExampleProcessor], "p1"))
  val p2 = system.actorOf(Props(classOf[ExampleProcessor], "p2"))

  val materializer = FlowMaterializer(MaterializerSettings())

  // 1 view-backed producer and 2 consumers:
  val producer1: Publisher[Persistent] = PersistentFlow.fromProcessor("p1").toPublisher(materializer)
  Flow(producer1).foreach { p => println(s"consumer-1: ${p.payload}") }.consume(materializer)
  Flow(producer1).foreach { p => println(s"consumer-2: ${p.payload}") }.consume(materializer)

  // 2 view-backed producers (merged) and 1 consumer:
  // This is an example how message/event streams from multiple processors can be merged into a single stream.
  val producer2: Publisher[Persistent] = PersistentFlow.fromProcessor("p1").toPublisher(materializer)
  val merged: Publisher[Persistent] = PersistentFlow.fromProcessor("p2").merge(producer2).toPublisher(materializer)
  Flow(merged).foreach { p => println(s"consumer-3: ${p.payload}") }.consume(materializer)

  while (true) {
    p1 ! Persistent("a-" + System.currentTimeMillis())
    p2 ! Persistent("b-" + System.currentTimeMillis())
    Thread.sleep(500)
  }
}

