/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package sample.persistence

//#eventsourced-example
import akka.actor._
import akka.persistence._

case class Cmd(data: String)
case class Evt(data: String)

case class ExampleState(events: List[String] = Nil) {
  def update(evt: Evt) = copy(evt.data :: events)
  def size = events.length
  override def toString: String = events.reverse.toString
}

class ExampleProcessor extends EventsourcedProcessor {
  var state = ExampleState()

  def updateState(event: Evt): Unit =
    state = state.update(event)

  def numEvents =
    state.size

  val receiveRecover: Receive = {
    case evt: Evt                                 => updateState(evt)
    case SnapshotOffer(_, snapshot: ExampleState) => state = snapshot
  }

  val receiveCommand: Receive = {
    case Cmd(data) =>
      persist(Evt(s"${data}-${numEvents}"))(updateState)
      persist(Evt(s"${data}-${numEvents + 1}")) { event =>
        updateState(event)
        context.system.eventStream.publish(event)
        if (data == "foo") context.become(otherCommandHandler)
      }
    case "snap"  => saveSnapshot(state)
    case "print" => println(state)
  }

  val otherCommandHandler: Receive = {
    case Cmd("bar") =>
      persist(Evt(s"bar-${numEvents}")) { event =>
        updateState(event)
        context.unbecome()
      }
      unstashAll()
    case other => stash()
  }
}
//#eventsourced-example

object EventsourcedExample extends App {

  val system = ActorSystem("example")
  val processor = system.actorOf(Props[ExampleProcessor], "processor-4-scala")

  processor ! Cmd("foo")
  processor ! Cmd("baz") // will be stashed
  processor ! Cmd("bar")
  processor ! "snap"
  processor ! Cmd("buzz")
  processor ! "print"

  Thread.sleep(1000)
  system.shutdown()
}
