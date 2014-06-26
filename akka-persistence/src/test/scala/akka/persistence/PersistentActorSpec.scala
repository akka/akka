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

object PersistentActorSpec {
  case class Cmd(data: Any)
  case class Evt(data: Any)

  abstract class ExamplePersistentActor(name: String) extends NamedProcessor(name) with PersistentActor {
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

  class Behavior1Processor(name: String) extends ExamplePersistentActor(name) {
    val receiveCommand: Receive = commonBehavior orElse {
      case Cmd(data) ⇒
        persist(Seq(Evt(s"${data}-1"), Evt(s"${data}-2")))(updateState)
    }
  }

  class Behavior2Processor(name: String) extends ExamplePersistentActor(name) {
    val receiveCommand: Receive = commonBehavior orElse {
      case Cmd(data) ⇒
        persist(Seq(Evt(s"${data}-1"), Evt(s"${data}-2")))(updateState)
        persist(Seq(Evt(s"${data}-3"), Evt(s"${data}-4")))(updateState)
    }
  }

  class Behavior3Processor(name: String) extends ExamplePersistentActor(name) {
    val receiveCommand: Receive = commonBehavior orElse {
      case Cmd(data) ⇒
        persist(Seq(Evt(s"${data}-11"), Evt(s"${data}-12")))(updateState)
        updateState(Evt(s"${data}-10"))
    }
  }

  class ChangeBehaviorInLastEventHandlerProcessor(name: String) extends ExamplePersistentActor(name) {
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

  class ChangeBehaviorInFirstEventHandlerProcessor(name: String) extends ExamplePersistentActor(name) {
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

  class ChangeBehaviorInCommandHandlerFirstProcessor(name: String) extends ExamplePersistentActor(name) {
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

  class ChangeBehaviorInCommandHandlerLastProcessor(name: String) extends ExamplePersistentActor(name) {
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
      case c: Cmd                              ⇒ handleCmd(c)
      case SaveSnapshotSuccess(_)              ⇒ probe ! "saved"
      case "snap"                              ⇒ saveSnapshot(events)
      case ConfirmablePersistent(c: Cmd, _, _) ⇒ handleCmd(c)
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

  class ReplyInEventHandlerProcessor(name: String) extends ExamplePersistentActor(name) {
    val receiveCommand: Receive = {
      case Cmd("a")      ⇒ persist(Evt("a"))(evt ⇒ sender() ! evt.data)
      case p: Persistent ⇒ sender() ! p // not expected
    }
  }

  class UserStashProcessor(name: String) extends ExamplePersistentActor(name) {
    var stashed = false
    val receiveCommand: Receive = {
      case Cmd("a") ⇒ if (!stashed) { stash(); stashed = true } else sender() ! "a"
      case Cmd("b") ⇒ persist(Evt("b"))(evt ⇒ sender() ! evt.data)
      case Cmd("c") ⇒ unstashAll(); sender() ! "c"
    }
  }

  class UserStashManyProcessor(name: String) extends ExamplePersistentActor(name) {
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
  class AsyncPersistProcessor(name: String) extends ExamplePersistentActor(name) {
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
  class AsyncPersistThreeTimesProcessor(name: String) extends ExamplePersistentActor(name) {
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
  class AsyncPersistSameEventTwiceProcessor(name: String) extends ExamplePersistentActor(name) {

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
  class AsyncPersistAndPersistMixedSyncAsyncSyncProcessor(name: String) extends ExamplePersistentActor(name) {

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
  class AsyncPersistAndPersistMixedSyncAsyncProcessor(name: String) extends ExamplePersistentActor(name) {

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

  class UserStashFailureProcessor(name: String) extends ExamplePersistentActor(name) {
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

  class AnyValEventProcessor(name: String) extends ExamplePersistentActor(name) {
    val receiveCommand: Receive = {
      case Cmd("a") ⇒ persist(5)(evt ⇒ sender() ! evt)
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

  class HandleRecoveryFinishedEventProcessor(name: String, probe: ActorRef) extends SnapshottingPersistentActor(name, probe) {
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
}

abstract class PersistentActorSpec(config: Config) extends AkkaSpec(config) with PersistenceSpec with ImplicitSender {
  import PersistentActorSpec._

  override protected def beforeEach() {
    super.beforeEach()

    val processor = namedProcessor[Behavior1Processor]
    processor ! Cmd("a")
    processor ! GetState
    expectMsg(List("a-1", "a-2"))
  }

  "A persistent actor" must {
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
      val processor1 = system.actorOf(Props(classOf[SnapshottingPersistentActor], name, testActor))
      processor1 ! Cmd("b")
      processor1 ! "snap"
      processor1 ! Cmd("c")
      expectMsg("saved")
      processor1 ! GetState
      expectMsg(List("a-1", "a-2", "b-41", "b-42", "c-41", "c-42"))

      val processor2 = system.actorOf(Props(classOf[SnapshottingPersistentActor], name, testActor))
      expectMsg("offered")
      processor2 ! GetState
      expectMsg(List("a-1", "a-2", "b-41", "b-42", "c-41", "c-42"))
    }
    "support context.become during recovery" in {
      val processor1 = system.actorOf(Props(classOf[SnapshottingPersistentActor], name, testActor))
      processor1 ! Cmd("b")
      processor1 ! "snap"
      processor1 ! Cmd("c")
      expectMsg("saved")
      processor1 ! GetState
      expectMsg(List("a-1", "a-2", "b-41", "b-42", "c-41", "c-42"))

      val processor2 = system.actorOf(Props(classOf[SnapshottingBecomingPersistentActor], name, testActor))
      expectMsg("offered")
      expectMsg("I am becoming")
      processor2 ! GetState
      expectMsg(List("a-1", "a-2", "b-41", "b-42", "c-41", "c-42"))
    }
    "support confirmable persistent" in {
      val processor1 = system.actorOf(Props(classOf[SnapshottingPersistentActor], name, testActor))
      processor1 ! Cmd("b")
      processor1 ! "snap"
      processor1 ! ConfirmablePersistentImpl(Cmd("c"), 4711, "some-id", false, 0, Seq.empty, null, null, null)
      expectMsg("saved")
      processor1 ! GetState
      expectMsg(List("a-1", "a-2", "b-41", "b-42", "c-41", "c-42"))

      val processor2 = system.actorOf(Props(classOf[SnapshottingPersistentActor], name, testActor))
      expectMsg("offered")
      processor2 ! GetState
      expectMsg(List("a-1", "a-2", "b-41", "b-42", "c-41", "c-42"))
    }
    "reject Persistent messages" in {
      val probe = TestProbe()
      val processor = namedProcessor[ReplyInEventHandlerProcessor]

      EventFilter[UnsupportedOperationException](occurrences = 1) intercept {
        processor.tell(Persistent("not allowed"), probe.ref)
      }

      processor.tell(Cmd("w"), probe.ref)
      processor.tell(Cmd("w"), probe.ref)
      processor.tell(Cmd("w"), probe.ref)
      EventFilter[UnsupportedOperationException](occurrences = 1) intercept {
        processor.tell(Persistent("not allowed when persisting"), probe.ref)
      }
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
    "be able to opt-out from stashing messages until all events have been processed" in {
      val processor = namedProcessor[AsyncPersistProcessor]
      processor ! Cmd("x")
      processor ! Cmd("y")
      expectMsg("x")
      expectMsg("y") // "y" command was processed before event persisted
      expectMsg("x-1")
      expectMsg("y-2")
    }
    "support multiple persistAsync calls for one command, and execute them 'when possible', not hindering command processing" in {
      val processor = namedProcessor[AsyncPersistThreeTimesProcessor]
      val commands = 1 to 10 map { i ⇒ Cmd(s"c-$i") }

      commands foreach { i ⇒
        Thread.sleep(Random.nextInt(10))
        processor ! i
      }

      val all: Seq[String] = this.receiveN(40).asInstanceOf[Seq[String]] // each command = 1 reply + 3 event-replies

      val replies = all.filter(r ⇒ r.count(_ == '-') == 1)
      replies should equal(commands.map(_.data))

      val expectedAcks = (3 to 32) map { i ⇒ s"a-${i / 3}-${i - 2}" }
      val acks = all.filter(r ⇒ r.count(_ == '-') == 2)
      acks should equal(expectedAcks)
    }
    "reply to the original sender() of a command, even when using persistAsync" in {
      // sanity check, the setting of sender() for PersistentRepl is handled by Processor currently
      // but as we want to remove it soon, keeping the explicit test here.
      val processor = namedProcessor[AsyncPersistThreeTimesProcessor]

      val commands = 1 to 10 map { i ⇒ Cmd(s"c-$i") }
      val probes = Vector.fill(10)(TestProbe())

      (probes zip commands) foreach {
        case (p, c) ⇒
          processor.tell(c, p.ref)
      }

      val ackClass = classOf[String]
      within(3.seconds) {
        probes foreach { _.expectMsgAllClassOf(ackClass, ackClass, ackClass) }
      }
    }
    "support the same event being asyncPersist'ed multiple times" in {
      val processor = namedProcessor[AsyncPersistSameEventTwiceProcessor]
      processor ! Cmd("x")
      expectMsg("x")

      expectMsg("x-a-1")
      expectMsg("x-b-2")
      expectNoMsg(100.millis)
    }
    "support a mix of persist calls (sync, async, sync) and persist calls in expected order" in {
      val processor = namedProcessor[AsyncPersistAndPersistMixedSyncAsyncSyncProcessor]
      processor ! Cmd("a")
      processor ! Cmd("b")
      processor ! Cmd("c")
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
      val processor = namedProcessor[AsyncPersistAndPersistMixedSyncAsyncProcessor]
      processor ! Cmd("a")
      processor ! Cmd("b")
      processor ! Cmd("c")
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
      val processor = namedProcessor[AsyncPersistHandlerCorrelationCheck]
      for (n ← 1 to 100) processor ! Cmd(n)
      processor ! "boom"
      for (n ← 1 to 20) processor ! Cmd(n)
      processor ! Cmd("done")
      expectMsg(5.seconds, "done")
    }
    "allow deferring handlers in order to provide ordered processing in respect to persist handlers" in {
      val processor = namedProcessor[DeferringWithPersistActor]
      processor ! Cmd("a")
      expectMsg("d-1")
      expectMsg("a-2")
      expectMsg("d-3")
      expectMsg("d-4")
      expectNoMsg(100.millis)
    }
    "allow deferring handlers in order to provide ordered processing in respect to asyncPersist handlers" in {
      val processor = namedProcessor[DeferringWithAsyncPersistActor]
      processor ! Cmd("a")
      expectMsg("d-a-1")
      expectMsg("pa-a-2")
      expectMsg("d-a-3")
      expectMsg("d-a-4")
      expectNoMsg(100.millis)
    }
    "invoke deferred handlers, in presence of mixed a long series persist / persistAsync calls" in {
      val processor = namedProcessor[DeferringMixedCallsPPADDPADPersistActor]
      val p1, p2 = TestProbe()

      processor.tell(Cmd("a"), p1.ref)
      processor.tell(Cmd("b"), p2.ref)
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
      val processor = namedProcessor[DeferringWithNoPersistCallsPersistActor]
      processor ! Cmd("a")
      expectMsg("d-1")
      expectMsg("d-2")
      expectMsg("d-3")
      expectNoMsg(100.millis)
    }
    "invoke deferred handlers, perserving the original sender references" in {
      val processor = namedProcessor[DeferringWithAsyncPersistActor]
      val p1, p2 = TestProbe()

      processor.tell(Cmd("a"), p1.ref)
      processor.tell(Cmd("b"), p2.ref)
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
      val processor1 = system.actorOf(Props(classOf[SnapshottingPersistentActor], name, testActor))
      processor1 ! Cmd("b")
      processor1 ! "snap"
      processor1 ! Cmd("c")
      expectMsg("saved")
      processor1 ! GetState
      expectMsg(List("a-1", "a-2", "b-41", "b-42", "c-41", "c-42"))

      val processor2 = system.actorOf(Props(classOf[HandleRecoveryFinishedEventProcessor], name, testActor))
      expectMsg("offered")
      expectMsg(RecoveryCompleted)
      expectMsg("I am the stashed")
      expectMsg("I am the recovered")
      processor2 ! GetState
      expectMsg(List("a-1", "a-2", "b-41", "b-42", "c-41", "c-42", RecoveryCompleted))
    }
  }
}

class LeveldbPersistentActorSpec extends PersistentActorSpec(PersistenceSpec.config("leveldb", "LeveldbPersistentActorSpec"))
class InmemPersistentActorSpec extends PersistentActorSpec(PersistenceSpec.config("inmem", "InmemPersistentActorSpec"))
