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

object EventsourcedSpec {
  case class Cmd(data: Any)
  case class Evt(data: Any)

  abstract class ExampleProcessor(name: String) extends NamedProcessor(name) with EventsourcedProcessor {
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
      case Cmd("a")      ⇒ persist(Evt("a"))(evt ⇒ sender() ! evt.data)
      case p: Persistent ⇒ sender() ! p // not expected
    }
  }

  class UserStashProcessor(name: String) extends ExampleProcessor(name) {
    var stashed = false
    val receiveCommand: Receive = {
      case Cmd("a") ⇒ if (!stashed) { stash(); stashed = true } else sender() ! "a"
      case Cmd("b") ⇒ persist(Evt("b"))(evt ⇒ sender() ! evt.data)
      case Cmd("c") ⇒ unstashAll(); sender() ! "c"
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
  class AsyncPersistProcessor(name: String) extends ExampleProcessor(name) {
    var counter = 0

    val receiveCommand: Receive = commonBehavior orElse {
      case Cmd(data) ⇒
        sender() ! data
        persistAsync(Evt(s"$data-${incCounter()}")) { evt ⇒
          Thread.sleep(100) // enough time for a command to hit the processor
          sender() ! evt.data
        }
    }

    private def incCounter(): Int = {
      counter += 1
      counter
    }
  }
  class AsyncPersistThreeTimesProcessor(name: String) extends ExampleProcessor(name) {
    var counter = 0

    val receiveCommand: Receive = commonBehavior orElse {
      case Cmd(data) ⇒
        sender() ! data

        1 to 3 foreach { i ⇒
          persistAsync(Evt(s"$data-${incCounter()}")) { evt ⇒
            Thread.sleep(10) // enough time for a command to hit the processor
            sender() ! ("a" + evt.data.toString.drop(1)) // c-1 => a-1, as in "ack"
          }
        }
    }

    private def incCounter(): Int = {
      counter += 1
      counter
    }
  }
  class AsyncPersistSameEventTwiceProcessor(name: String) extends ExampleProcessor(name) {

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
  class AsyncPersistAndPersistMixedSyncAsyncSyncProcessor(name: String) extends ExampleProcessor(name) {

    var counter = 0

    val start = System.currentTimeMillis()
    def time = s" ${System.currentTimeMillis() - start}ms"
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
  class AsyncPersistAndPersistMixedSyncAsyncProcessor(name: String) extends ExampleProcessor(name) {

    var sendMsgCounter = 0

    val start = System.currentTimeMillis()
    def time = s" ${System.currentTimeMillis() - start}ms"
    val receiveCommand: Receive = commonBehavior orElse {
      case Cmd(data) ⇒
        val replyTo = sender()
        sender() ! data

        Thread.sleep(10)
        persist(Evt(data + "-e1")) { evt ⇒
          sender() ! s"${evt.data}-${incCounter()}"
        }

        persistAsync(Evt(data + "-ea2")) { evt ⇒
          replyTo ! s"${evt.data}-${incCounter()}"
        }
    }

    def incCounter() = {
      sendMsgCounter += 1
      sendMsgCounter
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
      case Cmd("a") ⇒ persist(5)(evt ⇒ sender() ! evt)
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
    "reject Persistent messages" in {
      val probe = TestProbe()
      val processor = namedProcessor[ReplyInEventHandlerProcessor]

      EventFilter[UnsupportedOperationException](occurrences = 1) intercept {
        processor.tell(Persistent("not allowed"), probe.ref)
      }

      processor.tell(Cmd("a"), probe.ref)
      processor.tell(Cmd("a"), probe.ref)
      processor.tell(Cmd("a"), probe.ref)
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
    "be able to opt-out from stashing messages until all events have ben processed" in {
      val processor = namedProcessor[AsyncPersistProcessor]
      processor ! Cmd("x")
      processor ! Cmd("y")
      expectMsg("x")
      expectMsg("y") // "y" command was processed before event persisted
      val first = expectMsgAnyOf("x-1", "y-2")
      if (first contains "x") expectMsg("y-2") else expectMsg("x-1")
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

      // should be visible that command replies are intermingled with the ACKs
      info("messages received in order: ")
      info("" + all.take(10))
      info("" + all.drop(10).take(10))
      info("" + all.drop(20).take(10))
      info("" + all.drop(30).take(10))
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
      within(2.seconds) {
        probes foreach { _.expectMsgAllClassOf(ackClass, ackClass, ackClass) }
      }
    }
    "support the same event being asyncPersist'ed multiple times" in {
      val processor = namedProcessor[AsyncPersistSameEventTwiceProcessor]
      processor ! Cmd("x")
      expectMsg("x")

      // two callbacks are registered for the same event
      // each must be called once, order does not matter
      val first = expectMsgAnyOf("x-a-1", "x-b-1")
      if (first contains "a") expectMsg("x-b-2") else expectMsg("x-a-2")
      expectNoMsg(100.millis)
    }
    "support a mix of persist calls (sync, async, sync) and persist calls in expected order" in {
      val processor = namedProcessor[AsyncPersistAndPersistMixedSyncAsyncSyncProcessor]
      processor ! Cmd("a")
      processor ! Cmd("b")
      processor ! Cmd("c")
      info("received:" + expectMsg("a"))
      info("received:" + expectMsg("a-e1-1")) // persist
      info("received:" + expectMsg("a-ea2-2")) // persistAsync, but ordering enforced by sync persist below
      info("received:" + expectMsg("a-e3-3")) // persist

      info("received:" + expectMsg("b"))
      info("received:" + expectMsg("b-e1-4"))
      info("received:" + expectMsg("b-ea2-5"))
      info("received:" + expectMsg("b-e3-6"))

      info("received:" + expectMsg("c"))
      info("received:" + expectMsg("c-e1-7"))
      info("received:" + expectMsg("c-ea2-8"))
      info("received:" + expectMsg("c-e3-9"))

      expectNoMsg(100.millis)
    }
    "support a mix of persist calls (sync, async) and persist calls" in {
      val processor = namedProcessor[AsyncPersistAndPersistMixedSyncAsyncProcessor]
      processor ! Cmd("a")
      processor ! Cmd("b")
      processor ! Cmd("c")
      info("received:" + expectMsg("a"))
      info("received:" + expectMsg("a-e1-1"))

      info("received:" + expectMsg("b"))
      info("received:" + expectMsg("a-ea2-2")) // async, finished processing after b was already picked up
      info("received:" + expectMsg("b-e1-3"))

      info("received:" + expectMsg("c"))
      info("received:" + expectMsg("b-ea2-4")) // async, finished processing after c was already picked up
      info("received:" + expectMsg("c-e1-5"))
      info("received:" + expectMsg("c-ea2-6"))

      expectNoMsg(100.millis)
    }
  }
}

class LeveldbEventsourcedSpec extends EventsourcedSpec(PersistenceSpec.config("leveldb", "LeveldbEventsourcedSpec"))
class InmemEventsourcedSpec extends EventsourcedSpec(PersistenceSpec.config("inmem", "InmemEventsourcedSpec"))
