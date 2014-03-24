/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import scala.collection.immutable.Seq
import scala.concurrent.duration._

import com.typesafe.config.Config

import akka.actor._
import akka.testkit.{ ImplicitSender, AkkaSpec }

object EventsourcedSpec {
  final case class Cmd(data: Any)
  final case class Evt(data: Any)

  abstract class ExampleProcessor(name: String) extends NamedProcessor(name) with EventsourcedProcessor {
    var events: List[Any] = Nil

    val updateState: Receive = {
      case Evt(data) ⇒ events = data :: events
    }

    val commonBehavior: Receive = {
      case "boom"   ⇒ throw new TestException("boom")
      case GetState ⇒ sender ! events.reverse
    }

    def receiveRecover = updateState
  }

  class Behavior1Processor(name: String) extends ExampleProcessor(name) {
    val receiveCommand: Receive = commonBehavior orElse {
      case Cmd(data) ⇒
        persist(Seq(Evt(s"${data}-1"), Evt(s"${data}-2")))(updateState)
    }
  }

  class Behavior2Processor(name: String) extends ExampleProcessor(name) {
    val receiveCommand: Receive = commonBehavior orElse {
      case Cmd(data) ⇒
        persist(Seq(Evt(s"${data}-1"), Evt(s"${data}-2")))(updateState)
        persist(Seq(Evt(s"${data}-3"), Evt(s"${data}-4")))(updateState)
    }
  }

  class Behavior3Processor(name: String) extends ExampleProcessor(name) {
    val receiveCommand: Receive = commonBehavior orElse {
      case Cmd(data) ⇒
        persist(Seq(Evt(s"${data}-11"), Evt(s"${data}-12")))(updateState)
        updateState(Evt(s"${data}-10"))
    }
  }

  class ChangeBehaviorInLastEventHandlerProcessor(name: String) extends ExampleProcessor(name) {
    val newBehavior: Receive = {
      case Cmd(data) ⇒
        persist(Evt(s"${data}-21"))(updateState)
        persist(Evt(s"${data}-22")) { event ⇒
          updateState(event)
          context.unbecome()
        }
    }

    val receiveCommand: Receive = commonBehavior orElse {
      case Cmd(data) ⇒
        persist(Evt(s"${data}-0")) { event ⇒
          updateState(event)
          context.become(newBehavior)
        }
    }
  }

  class ChangeBehaviorInFirstEventHandlerProcessor(name: String) extends ExampleProcessor(name) {
    val newBehavior: Receive = {
      case Cmd(data) ⇒
        persist(Evt(s"${data}-21")) { event ⇒
          updateState(event)
          context.unbecome()
        }
        persist(Evt(s"${data}-22"))(updateState)
    }

    val receiveCommand: Receive = commonBehavior orElse {
      case Cmd(data) ⇒
        persist(Evt(s"${data}-0")) { event ⇒
          updateState(event)
          context.become(newBehavior)
        }
    }
  }

  class ChangeBehaviorInCommandHandlerFirstProcessor(name: String) extends ExampleProcessor(name) {
    val newBehavior: Receive = {
      case Cmd(data) ⇒
        context.unbecome()
        persist(Seq(Evt(s"${data}-31"), Evt(s"${data}-32")))(updateState)
        updateState(Evt(s"${data}-30"))
    }

    val receiveCommand: Receive = commonBehavior orElse {
      case Cmd(data) ⇒
        context.become(newBehavior)
        persist(Evt(s"${data}-0"))(updateState)
    }
  }

  class ChangeBehaviorInCommandHandlerLastProcessor(name: String) extends ExampleProcessor(name) {
    val newBehavior: Receive = {
      case Cmd(data) ⇒
        persist(Seq(Evt(s"${data}-31"), Evt(s"${data}-32")))(updateState)
        updateState(Evt(s"${data}-30"))
        context.unbecome()
    }

    val receiveCommand: Receive = commonBehavior orElse {
      case Cmd(data) ⇒
        persist(Evt(s"${data}-0"))(updateState)
        context.become(newBehavior)
    }
  }

  class SnapshottingEventsourcedProcessor(name: String, probe: ActorRef) extends ExampleProcessor(name) {
    override def receiveRecover = super.receiveRecover orElse {
      case SnapshotOffer(_, events: List[_]) ⇒
        probe ! "offered"
        this.events = events
    }

    private def handleCmd(cmd: Cmd): Unit = {
      persist(Seq(Evt(s"${cmd.data}-41"), Evt(s"${cmd.data}-42")))(updateState)
    }

    val receiveCommand: Receive = commonBehavior orElse {
      case c: Cmd                              ⇒ handleCmd(c)
      case SaveSnapshotSuccess(_)              ⇒ probe ! "saved"
      case "snap"                              ⇒ saveSnapshot(events)
      case ConfirmablePersistent(c: Cmd, _, _) ⇒ handleCmd(c)
    }
  }

  class SnapshottingBecomingEventsourcedProcessor(name: String, probe: ActorRef) extends SnapshottingEventsourcedProcessor(name, probe) {
    val becomingRecover: Receive = {
      case msg: SnapshotOffer ⇒
        context.become(becomingCommand)
        // sending ourself a normal message here also tests
        // that we stash them until recovery is complete
        self ! "It's changing me"
        super.receiveRecover(msg)
    }

    override def receiveRecover = becomingRecover.orElse(super.receiveRecover)

    val becomingCommand: Receive = receiveCommand orElse {
      case "It's changing me" ⇒ probe ! "I am becoming"
    }
  }

  class ReplyInEventHandlerProcessor(name: String) extends ExampleProcessor(name) {
    val receiveCommand: Receive = {
      case Cmd("a") ⇒ persist(Evt("a"))(evt ⇒ sender ! evt.data)
    }
  }

  class UserStashProcessor(name: String) extends ExampleProcessor(name) {
    var stashed = false
    val receiveCommand: Receive = {
      case Cmd("a") ⇒ if (!stashed) { stash(); stashed = true } else sender ! "a"
      case Cmd("b") ⇒ persist(Evt("b"))(evt ⇒ sender ! evt.data)
      case Cmd("c") ⇒ unstashAll(); sender ! "c"
    }
  }

  class UserStashManyProcessor(name: String) extends ExampleProcessor(name) {
    val receiveCommand: Receive = commonBehavior orElse {
      case Cmd("a") ⇒ persist(Evt("a")) { evt ⇒
        updateState(evt)
        context.become(processC)
      }
      case Cmd("b-1") ⇒ persist(Evt("b-1"))(updateState)
      case Cmd("b-2") ⇒ persist(Evt("b-2"))(updateState)
    }

    val processC: Receive = {
      case Cmd("c") ⇒
        persist(Evt("c")) { evt ⇒
          updateState(evt)
          context.unbecome()
        }
        unstashAll()
      case other ⇒ stash()
    }
  }

  class UserStashFailureProcessor(name: String) extends ExampleProcessor(name) {
    val receiveCommand: Receive = commonBehavior orElse {
      case Cmd(data) ⇒
        if (data == "b-2") throw new TestException("boom")
        persist(Evt(data)) { event ⇒
          updateState(event)
          if (data == "a") context.become(otherCommandHandler)
        }
    }

    val otherCommandHandler: Receive = {
      case Cmd("c") ⇒
        persist(Evt("c")) { event ⇒
          updateState(event)
          context.unbecome()
        }
        unstashAll()
      case other ⇒ stash()
    }
  }

  class AnyValEventProcessor(name: String) extends ExampleProcessor(name) {
    val receiveCommand: Receive = {
      case Cmd("a") ⇒ persist(5)(evt ⇒ sender ! evt)
    }
  }
}

abstract class EventsourcedSpec(config: Config) extends AkkaSpec(config) with PersistenceSpec with ImplicitSender {
  import EventsourcedSpec._

  override protected def beforeEach() {
    super.beforeEach()

    val processor = namedProcessor[Behavior1Processor]
    processor ! Cmd("a")
    processor ! GetState
    expectMsg(List("a-1", "a-2"))
  }

  "An eventsourced processor" must {
    "recover from persisted events" in {
      val processor = namedProcessor[Behavior1Processor]
      processor ! GetState
      expectMsg(List("a-1", "a-2"))
    }
    "handle multiple emitted events in correct order (for a single persist call)" in {
      val processor = namedProcessor[Behavior1Processor]
      processor ! Cmd("b")
      processor ! GetState
      expectMsg(List("a-1", "a-2", "b-1", "b-2"))
    }
    "handle multiple emitted events in correct order (for multiple persist calls)" in {
      val processor = namedProcessor[Behavior2Processor]
      processor ! Cmd("b")
      processor ! GetState
      expectMsg(List("a-1", "a-2", "b-1", "b-2", "b-3", "b-4"))
    }
    "receive emitted events immediately after command" in {
      val processor = namedProcessor[Behavior3Processor]
      processor ! Cmd("b")
      processor ! Cmd("c")
      processor ! GetState
      expectMsg(List("a-1", "a-2", "b-10", "b-11", "b-12", "c-10", "c-11", "c-12"))
    }
    "recover on command failure" in {
      val processor = namedProcessor[Behavior3Processor]
      processor ! Cmd("b")
      processor ! "boom"
      processor ! Cmd("c")
      processor ! GetState
      // cmd that was added to state before failure (b-10) is not replayed ...
      expectMsg(List("a-1", "a-2", "b-11", "b-12", "c-10", "c-11", "c-12"))
    }
    "allow behavior changes in event handler (when handling first event)" in {
      val processor = namedProcessor[ChangeBehaviorInFirstEventHandlerProcessor]
      processor ! Cmd("b")
      processor ! Cmd("c")
      processor ! Cmd("d")
      processor ! Cmd("e")
      processor ! GetState
      expectMsg(List("a-1", "a-2", "b-0", "c-21", "c-22", "d-0", "e-21", "e-22"))
    }
    "allow behavior changes in event handler (when handling last event)" in {
      val processor = namedProcessor[ChangeBehaviorInLastEventHandlerProcessor]
      processor ! Cmd("b")
      processor ! Cmd("c")
      processor ! Cmd("d")
      processor ! Cmd("e")
      processor ! GetState
      expectMsg(List("a-1", "a-2", "b-0", "c-21", "c-22", "d-0", "e-21", "e-22"))
    }
    "allow behavior changes in command handler (as first action)" in {
      val processor = namedProcessor[ChangeBehaviorInCommandHandlerFirstProcessor]
      processor ! Cmd("b")
      processor ! Cmd("c")
      processor ! Cmd("d")
      processor ! Cmd("e")
      processor ! GetState
      expectMsg(List("a-1", "a-2", "b-0", "c-30", "c-31", "c-32", "d-0", "e-30", "e-31", "e-32"))
    }
    "allow behavior changes in command handler (as last action)" in {
      val processor = namedProcessor[ChangeBehaviorInCommandHandlerLastProcessor]
      processor ! Cmd("b")
      processor ! Cmd("c")
      processor ! Cmd("d")
      processor ! Cmd("e")
      processor ! GetState
      expectMsg(List("a-1", "a-2", "b-0", "c-30", "c-31", "c-32", "d-0", "e-30", "e-31", "e-32"))
    }
    "support snapshotting" in {
      val processor1 = system.actorOf(Props(classOf[SnapshottingEventsourcedProcessor], name, testActor))
      processor1 ! Cmd("b")
      processor1 ! "snap"
      processor1 ! Cmd("c")
      expectMsg("saved")
      processor1 ! GetState
      expectMsg(List("a-1", "a-2", "b-41", "b-42", "c-41", "c-42"))

      val processor2 = system.actorOf(Props(classOf[SnapshottingEventsourcedProcessor], name, testActor))
      expectMsg("offered")
      processor2 ! GetState
      expectMsg(List("a-1", "a-2", "b-41", "b-42", "c-41", "c-42"))
    }
    "support context.become during recovery" in {
      val processor1 = system.actorOf(Props(classOf[SnapshottingEventsourcedProcessor], name, testActor))
      processor1 ! Cmd("b")
      processor1 ! "snap"
      processor1 ! Cmd("c")
      expectMsg("saved")
      processor1 ! GetState
      expectMsg(List("a-1", "a-2", "b-41", "b-42", "c-41", "c-42"))

      val processor2 = system.actorOf(Props(classOf[SnapshottingBecomingEventsourcedProcessor], name, testActor))
      expectMsg("offered")
      expectMsg("I am becoming")
      processor2 ! GetState
      expectMsg(List("a-1", "a-2", "b-41", "b-42", "c-41", "c-42"))
    }
    "support confirmable persistent" in {
      val processor1 = system.actorOf(Props(classOf[SnapshottingEventsourcedProcessor], name, testActor))
      processor1 ! Cmd("b")
      processor1 ! "snap"
      processor1 ! ConfirmablePersistentImpl(Cmd("c"), 4711, "some-id", false, 0, Seq.empty, null, null, null)
      expectMsg("saved")
      processor1 ! GetState
      expectMsg(List("a-1", "a-2", "b-41", "b-42", "c-41", "c-42"))

      val processor2 = system.actorOf(Props(classOf[SnapshottingEventsourcedProcessor], name, testActor))
      expectMsg("offered")
      processor2 ! GetState
      expectMsg(List("a-1", "a-2", "b-41", "b-42", "c-41", "c-42"))
    }
    "be able to reply within an event handler" in {
      val processor = namedProcessor[ReplyInEventHandlerProcessor]
      processor ! Cmd("a")
      expectMsg("a")
    }
    "support user stash operations" in {
      val processor = namedProcessor[UserStashProcessor]
      processor ! Cmd("a")
      processor ! Cmd("b")
      processor ! Cmd("c")
      expectMsg("b")
      expectMsg("c")
      expectMsg("a")
    }
    "support user stash operations with several stashed messages" in {
      val processor = namedProcessor[UserStashManyProcessor]
      val n = 10
      val cmds = 1 to n flatMap (_ ⇒ List(Cmd("a"), Cmd("b-1"), Cmd("b-2"), Cmd("c")))
      val evts = 1 to n flatMap (_ ⇒ List("a", "c", "b-1", "b-2"))

      cmds foreach (processor ! _)
      processor ! GetState
      expectMsg((List("a-1", "a-2") ++ evts))
    }
    "support user stash operations under failures" in {
      val processor = namedProcessor[UserStashFailureProcessor]
      val bs = 1 to 10 map ("b-" + _)
      processor ! Cmd("a")
      bs foreach (processor ! Cmd(_))
      processor ! Cmd("c")
      processor ! GetState
      expectMsg(List("a-1", "a-2", "a", "c") ++ bs.filter(_ != "b-2"))
    }
    "be able to persist events that extend AnyVal" in {
      val processor = namedProcessor[AnyValEventProcessor]
      processor ! Cmd("a")
      expectMsg(5)
    }
  }
}

class LeveldbEventsourcedSpec extends EventsourcedSpec(PersistenceSpec.config("leveldb", "LeveldbEventsourcedSpec"))
class InmemEventsourcedSpec extends EventsourcedSpec(PersistenceSpec.config("inmem", "InmemEventsourcedSpec"))
