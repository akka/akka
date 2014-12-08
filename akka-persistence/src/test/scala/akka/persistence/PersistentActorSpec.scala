/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import com.typesafe.config.Config
import akka.actor._
import akka.testkit.{ ImplicitSender, AkkaSpec }
import akka.testkit.EventFilter
import akka.testkit.TestProbe
import java.util.concurrent.atomic.AtomicInteger
import scala.util.Random
import scala.util.control.NoStackTrace
import akka.testkit.TestLatch
import scala.concurrent.Await

object PersistentActorSpec {
  final case class Cmd(data: Any)
  final case class Evt(data: Any)
  final case class LatchCmd(latch: TestLatch, data: Any) extends NoSerializationVerificationNeeded

  abstract class ExamplePersistentActor(name: String) extends NamedPersistentActor(name) with PersistentActor {
    var events: List[Any] = Nil

    val updateState: Receive = {
      case Evt(data) ⇒ events = data :: events
    }

    val commonBehavior: Receive = {
      case "boom"   ⇒ throw new TestException("boom")
      case GetState ⇒ sender() ! events.reverse
    }

    def receiveRecover = updateState
  }

  class Behavior1PersistentActor(name: String) extends ExamplePersistentActor(name) {
    val receiveCommand: Receive = commonBehavior orElse {
      case Cmd(data) ⇒
        persist(Seq(Evt(s"${data}-1"), Evt(s"${data}-2")))(updateState)
    }
  }

  class Behavior2PersistentActor(name: String) extends ExamplePersistentActor(name) {
    val receiveCommand: Receive = commonBehavior orElse {
      case Cmd(data) ⇒
        persist(Seq(Evt(s"${data}-1"), Evt(s"${data}-2")))(updateState)
        persist(Seq(Evt(s"${data}-3"), Evt(s"${data}-4")))(updateState)
    }
  }

  class Behavior3PersistentActor(name: String) extends ExamplePersistentActor(name) {
    val receiveCommand: Receive = commonBehavior orElse {
      case Cmd(data) ⇒
        persist(Seq(Evt(s"${data}-11"), Evt(s"${data}-12")))(updateState)
        updateState(Evt(s"${data}-10"))
    }
  }

  class ChangeBehaviorInLastEventHandlerPersistentActor(name: String) extends ExamplePersistentActor(name) {
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

  class ChangeBehaviorInFirstEventHandlerPersistentActor(name: String) extends ExamplePersistentActor(name) {
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

  class ChangeBehaviorInCommandHandlerFirstPersistentActor(name: String) extends ExamplePersistentActor(name) {
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

  class ChangeBehaviorInCommandHandlerLastPersistentActor(name: String) extends ExamplePersistentActor(name) {
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

  class SnapshottingPersistentActor(name: String, probe: ActorRef) extends ExamplePersistentActor(name) {
    override def receiveRecover = super.receiveRecover orElse {
      case SnapshotOffer(_, events: List[_]) ⇒
        probe ! "offered"
        this.events = events
    }

    private def handleCmd(cmd: Cmd): Unit = {
      persist(Seq(Evt(s"${cmd.data}-41"), Evt(s"${cmd.data}-42")))(updateState)
    }

    def receiveCommand: Receive = commonBehavior orElse {
      case c: Cmd                 ⇒ handleCmd(c)
      case SaveSnapshotSuccess(_) ⇒ probe ! "saved"
      case "snap"                 ⇒ saveSnapshot(events)
    }
  }

  class SnapshottingBecomingPersistentActor(name: String, probe: ActorRef) extends SnapshottingPersistentActor(name, probe) {
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

  class ReplyInEventHandlerPersistentActor(name: String) extends ExamplePersistentActor(name) {
    val receiveCommand: Receive = {
      case Cmd("a") ⇒ persist(Evt("a"))(evt ⇒ sender() ! evt.data)
    }
  }

  class UserStashPersistentActor(name: String) extends ExamplePersistentActor(name) {
    var stashed = false
    val receiveCommand: Receive = {
      case Cmd("a") ⇒ if (!stashed) { stash(); stashed = true } else sender() ! "a"
      case Cmd("b") ⇒ persist(Evt("b"))(evt ⇒ sender() ! evt.data)
      case Cmd("c") ⇒ unstashAll(); sender() ! "c"
    }
  }

  class UserStashManyPersistentActor(name: String) extends ExamplePersistentActor(name) {
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
  class AsyncPersistPersistentActor(name: String) extends ExamplePersistentActor(name) {
    var counter = 0

    val receiveCommand: Receive = commonBehavior orElse {
      case Cmd(data) ⇒
        sender() ! data
        persistAsync(Evt(s"$data-${incCounter()}")) { evt ⇒
          sender() ! evt.data
        }
    }

    private def incCounter(): Int = {
      counter += 1
      counter
    }
  }
  class AsyncPersistThreeTimesPersistentActor(name: String) extends ExamplePersistentActor(name) {
    var counter = 0

    val receiveCommand: Receive = commonBehavior orElse {
      case Cmd(data) ⇒
        sender() ! data

        1 to 3 foreach { i ⇒
          persistAsync(Evt(s"$data-${incCounter()}")) { evt ⇒
            sender() ! ("a" + evt.data.toString.drop(1)) // c-1 => a-1, as in "ack"
          }
        }
    }

    private def incCounter(): Int = {
      counter += 1
      counter
    }
  }
  class AsyncPersistSameEventTwicePersistentActor(name: String) extends ExamplePersistentActor(name) {

    // atomic because used from inside the *async* callbacks
    val sendMsgCounter = new AtomicInteger()

    val receiveCommand: Receive = commonBehavior orElse {
      case Cmd(data) ⇒
        sender() ! data
        val event = Evt(data)

        persistAsync(event) { evt ⇒
          // be way slower, in order to be overtaken by the other callback
          Thread.sleep(300)
          sender() ! s"${evt.data}-a-${sendMsgCounter.incrementAndGet()}"
        }
        persistAsync(event) { evt ⇒ sender() ! s"${evt.data}-b-${sendMsgCounter.incrementAndGet()}" }
    }
  }
  class AsyncPersistAndPersistMixedSyncAsyncSyncPersistentActor(name: String) extends ExamplePersistentActor(name) {

    var counter = 0

    val receiveCommand: Receive = commonBehavior orElse {
      case Cmd(data) ⇒
        sender() ! data

        persist(Evt(data + "-e1")) { evt ⇒
          sender() ! s"${evt.data}-${incCounter()}"
        }

        // this should be happily executed
        persistAsync(Evt(data + "-ea2")) { evt ⇒
          sender() ! s"${evt.data}-${incCounter()}"
        }

        persist(Evt(data + "-e3")) { evt ⇒
          sender() ! s"${evt.data}-${incCounter()}"
        }
    }

    private def incCounter(): Int = {
      counter += 1
      counter
    }
  }
  class AsyncPersistAndPersistMixedSyncAsyncPersistentActor(name: String) extends ExamplePersistentActor(name) {

    var sendMsgCounter = 0

    val receiveCommand: Receive = commonBehavior orElse {
      case Cmd(data) ⇒
        sender() ! data

        persist(Evt(data + "-e1")) { evt ⇒
          sender() ! s"${evt.data}-${incCounter()}"
        }

        persistAsync(Evt(data + "-ea2")) { evt ⇒
          sender() ! s"${evt.data}-${incCounter()}"
        }
    }

    def incCounter() = {
      sendMsgCounter += 1
      sendMsgCounter
    }
  }

  class AsyncPersistHandlerCorrelationCheck(name: String) extends ExamplePersistentActor(name) {
    var counter = 0

    val receiveCommand: Receive = commonBehavior orElse {
      case Cmd(data) ⇒
        persistAsync(Evt(data)) { evt ⇒
          if (data != evt.data)
            sender() ! s"Expected [$data] bot got [${evt.data}]"
          if (evt.data == "done")
            sender() ! "done"
        }
    }

    private def incCounter(): Int = {
      counter += 1
      counter
    }
  }

  class UserStashFailurePersistentActor(name: String) extends ExamplePersistentActor(name) {
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

  class AnyValEventPersistentActor(name: String) extends ExamplePersistentActor(name) {
    val receiveCommand: Receive = {
      case Cmd("a") ⇒ persist(5)(evt ⇒ sender() ! evt)
    }
  }

  class HandleRecoveryFinishedEventPersistentActor(name: String, probe: ActorRef) extends SnapshottingPersistentActor(name, probe) {
    val sendingRecover: Receive = {
      case msg: SnapshotOffer ⇒
        // sending ourself a normal message tests
        // that we stash them until recovery is complete
        self ! "I am the stashed"
        super.receiveRecover(msg)
      case RecoveryCompleted ⇒
        probe ! RecoveryCompleted
        self ! "I am the recovered"
        updateState(Evt(RecoveryCompleted))
    }

    override def receiveRecover = sendingRecover.orElse(super.receiveRecover)

    override def receiveCommand: Receive = super.receiveCommand orElse {
      case s: String ⇒ probe ! s
    }

  }
  class DeferringWithPersistActor(name: String) extends ExamplePersistentActor(name) {
    val receiveCommand: Receive = {
      case Cmd(data) ⇒
        defer("d-1") { sender() ! _ }
        persist(s"$data-2") { sender() ! _ }
        defer("d-3") { sender() ! _ }
        defer("d-4") { sender() ! _ }
    }
  }
  class DeferringWithAsyncPersistActor(name: String) extends ExamplePersistentActor(name) {
    val receiveCommand: Receive = {
      case Cmd(data) ⇒
        defer(s"d-$data-1") { sender() ! _ }
        persistAsync(s"pa-$data-2") { sender() ! _ }
        defer(s"d-$data-3") { sender() ! _ }
        defer(s"d-$data-4") { sender() ! _ }
    }
  }
  class DeferringMixedCallsPPADDPADPersistActor(name: String) extends ExamplePersistentActor(name) {
    val receiveCommand: Receive = {
      case Cmd(data) ⇒
        persist(s"p-$data-1") { sender() ! _ }
        persistAsync(s"pa-$data-2") { sender() ! _ }
        defer(s"d-$data-3") { sender() ! _ }
        defer(s"d-$data-4") { sender() ! _ }
        persistAsync(s"pa-$data-5") { sender() ! _ }
        defer(s"d-$data-6") { sender() ! _ }
    }
  }
  class DeferringWithNoPersistCallsPersistActor(name: String) extends ExamplePersistentActor(name) {
    val receiveCommand: Receive = {
      case Cmd(data) ⇒
        defer("d-1") { sender() ! _ }
        defer("d-2") { sender() ! _ }
        defer("d-3") { sender() ! _ }
    }
  }

  class StressOrdering(name: String) extends ExamplePersistentActor(name) {
    val receiveCommand: Receive = {
      case LatchCmd(latch, data) ⇒
        sender() ! data
        Await.ready(latch, 5.seconds)
        persistAsync(data)(_ ⇒ ())
      case Cmd(data) ⇒
        sender() ! data
        persist(data)(_ ⇒ ())
      case s: String ⇒
        sender() ! s
    }
  }

  class StackableTestPersistentActor(val probe: ActorRef) extends StackableTestPersistentActor.BaseActor with PersistentActor with StackableTestPersistentActor.MixinActor {
    override def persistenceId: String = "StackableTestPersistentActor"

    def receiveCommand = {
      case "restart" ⇒ throw new Exception("triggering restart") with NoStackTrace { override def toString = "Boom!" }
    }

    def receiveRecover = {
      case _ ⇒ ()
    }

    override def preStart(): Unit = {
      probe ! "preStart"
      super.preStart()
    }

    override def postStop(): Unit = {
      probe ! "postStop"
      super.postStop()
    }

    override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
      probe ! "preRestart"
      super.preRestart(reason, message)
    }

    override def postRestart(reason: Throwable): Unit = {
      probe ! "postRestart"
      super.postRestart(reason)
    }

  }

  object StackableTestPersistentActor {
    trait BaseActor extends Actor { this: StackableTestPersistentActor ⇒
      override protected[akka] def aroundPreStart() = {
        probe ! "base aroundPreStart"
        super.aroundPreStart()
      }

      override protected[akka] def aroundPostStop() = {
        probe ! "base aroundPostStop"
        super.aroundPostStop()
      }

      override protected[akka] def aroundPreRestart(reason: Throwable, message: Option[Any]) = {
        probe ! "base aroundPreRestart"
        super.aroundPreRestart(reason, message)
      }

      override protected[akka] def aroundPostRestart(reason: Throwable) = {
        probe ! "base aroundPostRestart"
        super.aroundPostRestart(reason)
      }

      override protected[akka] def aroundReceive(receive: Receive, message: Any) = {
        if (message == "restart" && recoveryFinished) { probe ! s"base aroundReceive $message" }
        super.aroundReceive(receive, message)
      }
    }

    trait MixinActor extends Actor { this: StackableTestPersistentActor ⇒
      override protected[akka] def aroundPreStart() = {
        probe ! "mixin aroundPreStart"
        super.aroundPreStart()
      }

      override protected[akka] def aroundPostStop() = {
        probe ! "mixin aroundPostStop"
        super.aroundPostStop()
      }

      override protected[akka] def aroundPreRestart(reason: Throwable, message: Option[Any]) = {
        probe ! "mixin aroundPreRestart"
        super.aroundPreRestart(reason, message)
      }

      override protected[akka] def aroundPostRestart(reason: Throwable) = {
        probe ! "mixin aroundPostRestart"
        super.aroundPostRestart(reason)
      }

      override protected[akka] def aroundReceive(receive: Receive, message: Any) = {
        if (message == "restart" && recoveryFinished) { probe ! s"mixin aroundReceive $message" }
        super.aroundReceive(receive, message)
      }
    }
  }

}

abstract class PersistentActorSpec(config: Config) extends AkkaSpec(config) with PersistenceSpec with ImplicitSender {
  import PersistentActorSpec._

  override protected def beforeEach() {
    super.beforeEach()

    val persistentActor = namedPersistentActor[Behavior1PersistentActor]
    persistentActor ! Cmd("a")
    persistentActor ! GetState
    expectMsg(List("a-1", "a-2"))
  }

  "A persistent actor" must {
    "recover from persisted events" in {
      val persistentActor = namedPersistentActor[Behavior1PersistentActor]
      persistentActor ! GetState
      expectMsg(List("a-1", "a-2"))
    }
    "handle multiple emitted events in correct order (for a single persist call)" in {
      val persistentActor = namedPersistentActor[Behavior1PersistentActor]
      persistentActor ! Cmd("b")
      persistentActor ! GetState
      expectMsg(List("a-1", "a-2", "b-1", "b-2"))
    }
    "handle multiple emitted events in correct order (for multiple persist calls)" in {
      val persistentActor = namedPersistentActor[Behavior2PersistentActor]
      persistentActor ! Cmd("b")
      persistentActor ! GetState
      expectMsg(List("a-1", "a-2", "b-1", "b-2", "b-3", "b-4"))
    }
    "receive emitted events immediately after command" in {
      val persistentActor = namedPersistentActor[Behavior3PersistentActor]
      persistentActor ! Cmd("b")
      persistentActor ! Cmd("c")
      persistentActor ! GetState
      expectMsg(List("a-1", "a-2", "b-10", "b-11", "b-12", "c-10", "c-11", "c-12"))
    }
    "recover on command failure" in {
      val persistentActor = namedPersistentActor[Behavior3PersistentActor]
      persistentActor ! Cmd("b")
      persistentActor ! "boom"
      persistentActor ! Cmd("c")
      persistentActor ! GetState
      // cmd that was added to state before failure (b-10) is not replayed ...
      expectMsg(List("a-1", "a-2", "b-11", "b-12", "c-10", "c-11", "c-12"))
    }
    "allow behavior changes in event handler (when handling first event)" in {
      val persistentActor = namedPersistentActor[ChangeBehaviorInFirstEventHandlerPersistentActor]
      persistentActor ! Cmd("b")
      persistentActor ! Cmd("c")
      persistentActor ! Cmd("d")
      persistentActor ! Cmd("e")
      persistentActor ! GetState
      expectMsg(List("a-1", "a-2", "b-0", "c-21", "c-22", "d-0", "e-21", "e-22"))
    }
    "allow behavior changes in event handler (when handling last event)" in {
      val persistentActor = namedPersistentActor[ChangeBehaviorInLastEventHandlerPersistentActor]
      persistentActor ! Cmd("b")
      persistentActor ! Cmd("c")
      persistentActor ! Cmd("d")
      persistentActor ! Cmd("e")
      persistentActor ! GetState
      expectMsg(List("a-1", "a-2", "b-0", "c-21", "c-22", "d-0", "e-21", "e-22"))
    }
    "allow behavior changes in command handler (as first action)" in {
      val persistentActor = namedPersistentActor[ChangeBehaviorInCommandHandlerFirstPersistentActor]
      persistentActor ! Cmd("b")
      persistentActor ! Cmd("c")
      persistentActor ! Cmd("d")
      persistentActor ! Cmd("e")
      persistentActor ! GetState
      expectMsg(List("a-1", "a-2", "b-0", "c-30", "c-31", "c-32", "d-0", "e-30", "e-31", "e-32"))
    }
    "allow behavior changes in command handler (as last action)" in {
      val persistentActor = namedPersistentActor[ChangeBehaviorInCommandHandlerLastPersistentActor]
      persistentActor ! Cmd("b")
      persistentActor ! Cmd("c")
      persistentActor ! Cmd("d")
      persistentActor ! Cmd("e")
      persistentActor ! GetState
      expectMsg(List("a-1", "a-2", "b-0", "c-30", "c-31", "c-32", "d-0", "e-30", "e-31", "e-32"))
    }
    "support snapshotting" in {
      val persistentActor1 = system.actorOf(Props(classOf[SnapshottingPersistentActor], name, testActor))
      persistentActor1 ! Cmd("b")
      persistentActor1 ! "snap"
      persistentActor1 ! Cmd("c")
      expectMsg("saved")
      persistentActor1 ! GetState
      expectMsg(List("a-1", "a-2", "b-41", "b-42", "c-41", "c-42"))

      val persistentActor2 = system.actorOf(Props(classOf[SnapshottingPersistentActor], name, testActor))
      expectMsg("offered")
      persistentActor2 ! GetState
      expectMsg(List("a-1", "a-2", "b-41", "b-42", "c-41", "c-42"))
    }
    "support context.become during recovery" in {
      val persistentActor1 = system.actorOf(Props(classOf[SnapshottingPersistentActor], name, testActor))
      persistentActor1 ! Cmd("b")
      persistentActor1 ! "snap"
      persistentActor1 ! Cmd("c")
      expectMsg("saved")
      persistentActor1 ! GetState
      expectMsg(List("a-1", "a-2", "b-41", "b-42", "c-41", "c-42"))

      val persistentActor2 = system.actorOf(Props(classOf[SnapshottingBecomingPersistentActor], name, testActor))
      expectMsg("offered")
      expectMsg("I am becoming")
      persistentActor2 ! GetState
      expectMsg(List("a-1", "a-2", "b-41", "b-42", "c-41", "c-42"))
    }
    "be able to reply within an event handler" in {
      val persistentActor = namedPersistentActor[ReplyInEventHandlerPersistentActor]
      persistentActor ! Cmd("a")
      expectMsg("a")
    }
    "support user stash operations" in {
      val persistentActor = namedPersistentActor[UserStashPersistentActor]
      persistentActor ! Cmd("a")
      persistentActor ! Cmd("b")
      persistentActor ! Cmd("c")
      expectMsg("b")
      expectMsg("c")
      expectMsg("a")
    }
    "support user stash operations with several stashed messages" in {
      val persistentActor = namedPersistentActor[UserStashManyPersistentActor]
      val n = 10
      val cmds = 1 to n flatMap (_ ⇒ List(Cmd("a"), Cmd("b-1"), Cmd("b-2"), Cmd("c")))
      val evts = 1 to n flatMap (_ ⇒ List("a", "c", "b-1", "b-2"))

      cmds foreach (persistentActor ! _)
      persistentActor ! GetState
      expectMsg((List("a-1", "a-2") ++ evts))
    }
    "support user stash operations under failures" in {
      val persistentActor = namedPersistentActor[UserStashFailurePersistentActor]
      val bs = 1 to 10 map ("b-" + _)
      persistentActor ! Cmd("a")
      bs foreach (persistentActor ! Cmd(_))
      persistentActor ! Cmd("c")
      persistentActor ! GetState
      expectMsg(List("a-1", "a-2", "a", "c") ++ bs.filter(_ != "b-2"))
    }
    "be able to persist events that extend AnyVal" in {
      val persistentActor = namedPersistentActor[AnyValEventPersistentActor]
      persistentActor ! Cmd("a")
      expectMsg(5)
    }
    "be able to opt-out from stashing messages until all events have been processed" in {
      val persistentActor = namedPersistentActor[AsyncPersistPersistentActor]
      persistentActor ! Cmd("x")
      persistentActor ! Cmd("y")
      expectMsg("x")
      expectMsg("y") // "y" command was processed before event persisted
      expectMsg("x-1")
      expectMsg("y-2")
    }
    "support multiple persistAsync calls for one command, and execute them 'when possible', not hindering command processing" in {
      val persistentActor = namedPersistentActor[AsyncPersistThreeTimesPersistentActor]
      val commands = 1 to 10 map { i ⇒ Cmd(s"c-$i") }

      commands foreach { i ⇒
        Thread.sleep(Random.nextInt(10))
        persistentActor ! i
      }

      val all: Seq[String] = this.receiveN(40).asInstanceOf[Seq[String]] // each command = 1 reply + 3 event-replies

      val replies = all.filter(r ⇒ r.count(_ == '-') == 1)
      replies should equal(commands.map(_.data))

      val expectedAcks = (3 to 32) map { i ⇒ s"a-${i / 3}-${i - 2}" }
      val acks = all.filter(r ⇒ r.count(_ == '-') == 2)
      acks should equal(expectedAcks)
    }
    "reply to the original sender() of a command, even when using persistAsync" in {
      // sanity check, the setting of sender() for PersistentRepl is handled by PersistentActor currently
      // but as we want to remove it soon, keeping the explicit test here.
      val persistentActor = namedPersistentActor[AsyncPersistThreeTimesPersistentActor]

      val commands = 1 to 10 map { i ⇒ Cmd(s"c-$i") }
      val probes = Vector.fill(10)(TestProbe())

      (probes zip commands) foreach {
        case (p, c) ⇒
          persistentActor.tell(c, p.ref)
      }

      val ackClass = classOf[String]
      within(3.seconds) {
        probes foreach { _.expectMsgAllClassOf(ackClass, ackClass, ackClass) }
      }
    }
    "support the same event being asyncPersist'ed multiple times" in {
      val persistentActor = namedPersistentActor[AsyncPersistSameEventTwicePersistentActor]
      persistentActor ! Cmd("x")
      expectMsg("x")

      expectMsg("x-a-1")
      expectMsg("x-b-2")
      expectNoMsg(100.millis)
    }
    "support a mix of persist calls (sync, async, sync) and persist calls in expected order" in {
      val persistentActor = namedPersistentActor[AsyncPersistAndPersistMixedSyncAsyncSyncPersistentActor]
      persistentActor ! Cmd("a")
      persistentActor ! Cmd("b")
      persistentActor ! Cmd("c")
      expectMsg("a")
      expectMsg("a-e1-1") // persist
      expectMsg("a-ea2-2") // persistAsync, but ordering enforced by sync persist below
      expectMsg("a-e3-3") // persist
      expectMsg("b")
      expectMsg("b-e1-4")
      expectMsg("b-ea2-5")
      expectMsg("b-e3-6")
      expectMsg("c")
      expectMsg("c-e1-7")
      expectMsg("c-ea2-8")
      expectMsg("c-e3-9")

      expectNoMsg(100.millis)
    }
    "support a mix of persist calls (sync, async) and persist calls" in {
      val persistentActor = namedPersistentActor[AsyncPersistAndPersistMixedSyncAsyncPersistentActor]
      persistentActor ! Cmd("a")
      persistentActor ! Cmd("b")
      persistentActor ! Cmd("c")
      expectMsg("a")
      expectMsg("a-e1-1") // persist, must be before next command

      var expectInAnyOrder1 = Set("b", "a-ea2-2")
      expectInAnyOrder1 -= expectMsgAnyOf(expectInAnyOrder1.toList: _*) // ea2 is persistAsync, b (command) can processed before it
      expectMsgAnyOf(expectInAnyOrder1.toList: _*)

      expectMsg("b-e1-3") // persist, must be before next command

      var expectInAnyOrder2 = Set("c", "b-ea2-4")
      expectInAnyOrder2 -= expectMsgAnyOf(expectInAnyOrder2.toList: _*) // ea2 is persistAsync, b (command) can processed before it
      expectMsgAnyOf(expectInAnyOrder2.toList: _*)

      expectMsg("c-e1-5")
      expectMsg("c-ea2-6")

      expectNoMsg(100.millis)
    }
    "correlate persistAsync handlers after restart" in {
      val persistentActor = namedPersistentActor[AsyncPersistHandlerCorrelationCheck]
      for (n ← 1 to 100) persistentActor ! Cmd(n)
      persistentActor ! "boom"
      for (n ← 1 to 20) persistentActor ! Cmd(n)
      persistentActor ! Cmd("done")
      expectMsg(5.seconds, "done")
    }
    "allow deferring handlers in order to provide ordered processing in respect to persist handlers" in {
      val persistentActor = namedPersistentActor[DeferringWithPersistActor]
      persistentActor ! Cmd("a")
      expectMsg("d-1")
      expectMsg("a-2")
      expectMsg("d-3")
      expectMsg("d-4")
      expectNoMsg(100.millis)
    }
    "allow deferring handlers in order to provide ordered processing in respect to asyncPersist handlers" in {
      val persistentActor = namedPersistentActor[DeferringWithAsyncPersistActor]
      persistentActor ! Cmd("a")
      expectMsg("d-a-1")
      expectMsg("pa-a-2")
      expectMsg("d-a-3")
      expectMsg("d-a-4")
      expectNoMsg(100.millis)
    }
    "invoke deferred handlers, in presence of mixed a long series persist / persistAsync calls" in {
      val persistentActor = namedPersistentActor[DeferringMixedCallsPPADDPADPersistActor]
      val p1, p2 = TestProbe()

      persistentActor.tell(Cmd("a"), p1.ref)
      persistentActor.tell(Cmd("b"), p2.ref)
      p1.expectMsg("p-a-1")
      p1.expectMsg("pa-a-2")
      p1.expectMsg("d-a-3")
      p1.expectMsg("d-a-4")
      p1.expectMsg("pa-a-5")
      p1.expectMsg("d-a-6")

      p2.expectMsg("p-b-1")
      p2.expectMsg("pa-b-2")
      p2.expectMsg("d-b-3")
      p2.expectMsg("d-b-4")
      p2.expectMsg("pa-b-5")
      p2.expectMsg("d-b-6")

      expectNoMsg(100.millis)
    }
    "invoke deferred handlers right away, if there are no pending persist handlers registered" in {
      val persistentActor = namedPersistentActor[DeferringWithNoPersistCallsPersistActor]
      persistentActor ! Cmd("a")
      expectMsg("d-1")
      expectMsg("d-2")
      expectMsg("d-3")
      expectNoMsg(100.millis)
    }
    "invoke deferred handlers, perserving the original sender references" in {
      val persistentActor = namedPersistentActor[DeferringWithAsyncPersistActor]
      val p1, p2 = TestProbe()

      persistentActor.tell(Cmd("a"), p1.ref)
      persistentActor.tell(Cmd("b"), p2.ref)
      p1.expectMsg("d-a-1")
      p1.expectMsg("pa-a-2")
      p1.expectMsg("d-a-3")
      p1.expectMsg("d-a-4")

      p2.expectMsg("d-b-1")
      p2.expectMsg("pa-b-2")
      p2.expectMsg("d-b-3")
      p2.expectMsg("d-b-4")
      expectNoMsg(100.millis)
    }
    "receive RecoveryFinished if it is handled after all events have been replayed" in {
      val persistentActor1 = system.actorOf(Props(classOf[SnapshottingPersistentActor], name, testActor))
      persistentActor1 ! Cmd("b")
      persistentActor1 ! "snap"
      persistentActor1 ! Cmd("c")
      expectMsg("saved")
      persistentActor1 ! GetState
      expectMsg(List("a-1", "a-2", "b-41", "b-42", "c-41", "c-42"))

      val persistentActor2 = system.actorOf(Props(classOf[HandleRecoveryFinishedEventPersistentActor], name, testActor))
      expectMsg("offered")
      expectMsg(RecoveryCompleted)
      expectMsg("I am the stashed")
      expectMsg("I am the recovered")
      persistentActor2 ! GetState
      expectMsg(List("a-1", "a-2", "b-41", "b-42", "c-41", "c-42", RecoveryCompleted))
    }
    "preserv order of incoming messages" in {
      val persistentActor = namedPersistentActor[StressOrdering]
      persistentActor ! Cmd("a")
      val latch = TestLatch(1)
      persistentActor ! LatchCmd(latch, "b")
      persistentActor ! "c"
      expectMsg("a")
      expectMsg("b")
      persistentActor ! "d"
      latch.countDown()
      expectMsg("c")
      expectMsg("d")
    }
    "be used as a stackable modification" in {
      val persistentActor = system.actorOf(Props(classOf[StackableTestPersistentActor], testActor))
      expectMsg("mixin aroundPreStart")
      expectMsg("base aroundPreStart")
      expectMsg("preStart")

      persistentActor ! "restart"
      expectMsg("mixin aroundReceive restart")
      expectMsg("base aroundReceive restart")

      expectMsg("mixin aroundPreRestart")
      expectMsg("base aroundPreRestart")
      expectMsg("preRestart")
      expectMsg("postStop")

      expectMsg("mixin aroundPostRestart")
      expectMsg("base aroundPostRestart")
      expectMsg("postRestart")
      expectMsg("preStart")

      persistentActor ! PoisonPill
      expectMsg("mixin aroundPostStop")
      expectMsg("base aroundPostStop")
      expectMsg("postStop")

      expectNoMsg(100.millis)
    }

  }

}

class LeveldbPersistentActorSpec extends PersistentActorSpec(PersistenceSpec.config("leveldb", "LeveldbPersistentActorSpec"))
class InmemPersistentActorSpec extends PersistentActorSpec(PersistenceSpec.config("inmem", "InmemPersistentActorSpec"))
