/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import scala.concurrent.duration._
import scala.language.postfixOps

import com.typesafe.config._

import akka.actor._
import akka.testkit._

object ProcessorChannelSpec {
  class TestProcessor(name: String) extends NamedProcessor(name) {
    val destination = context.actorOf(Props[TestDestination])
    val channel = context.actorOf(Channel.props(s"${name}-channel"))

    def receive = {
      case m @ Persistent(s: String, _) if s.startsWith("a") ⇒
        // forward to destination via channel,
        // destination replies to initial sender
        channel forward Deliver(m.withPayload(s"fw: ${s}"), destination.path)
      case m @ Persistent(s: String, _) if s.startsWith("b") ⇒
        // reply to sender via channel
        channel ! Deliver(m.withPayload(s"re: ${s}"), sender.path)
    }
  }

  class TestDestination extends Actor {
    def receive = {
      case m: Persistent ⇒ sender ! m
    }
  }

  class ResendingProcessor(name: String, destination: ActorRef) extends NamedProcessor(name) {
    val channel = context.actorOf(Channel.props("channel", ChannelSettings(redeliverMax = 1, redeliverInterval = 100 milliseconds)))

    def receive = {
      case p: Persistent ⇒ channel ! Deliver(p, destination.path)
      case "replay"      ⇒ throw new TestException("replay requested")
    }
  }

  class ResendingEventsourcedProcessor(name: String, destination: ActorRef) extends NamedProcessor(name) with EventsourcedProcessor {
    val channel = context.actorOf(Channel.props("channel", ChannelSettings(redeliverMax = 1, redeliverInterval = 100 milliseconds)))

    var events: List[String] = Nil

    def handleEvent(event: String) = {
      events = event :: events
      channel ! Deliver(Persistent(event), destination.path)
    }

    def receiveReplay: Receive = {
      case event: String ⇒ handleEvent(event)
    }

    def receiveCommand: Receive = {
      case "cmd"    ⇒ persist("evt")(handleEvent)
      case "replay" ⇒ throw new TestException("replay requested")
    }
  }
}

abstract class ProcessorChannelSpec(config: Config) extends AkkaSpec(config) with PersistenceSpec with ImplicitSender {
  import ProcessorChannelSpec._

  private var processor: ActorRef = _

  override protected def beforeEach: Unit = {
    super.beforeEach()
    setupTestProcessorData()
    processor = createTestProcessor()
  }

  override protected def afterEach(): Unit = {
    system.stop(processor)
    super.afterEach()
  }

  def subscribeToConfirmation(probe: TestProbe): Unit =
    system.eventStream.subscribe(probe.ref, classOf[Delivered])

  def awaitConfirmation(probe: TestProbe): Unit =
    probe.expectMsgType[Delivered]

  def createTestProcessor(): ActorRef =
    system.actorOf(Props(classOf[TestProcessor], name))

  def setupTestProcessorData(): Unit = {
    val confirmProbe = TestProbe()
    val forwardProbe = TestProbe()
    val replyProbe = TestProbe()

    val processor = createTestProcessor()

    subscribeToConfirmation(confirmProbe)

    processor tell (Persistent("a1"), forwardProbe.ref)
    processor tell (Persistent("b1"), replyProbe.ref)

    forwardProbe.expectMsgPF() { case m @ ConfirmablePersistent("fw: a1", _, _) ⇒ m.confirm() }
    replyProbe.expectMsgPF() { case m @ ConfirmablePersistent("re: b1", _, _) ⇒ m.confirm() }

    awaitConfirmation(confirmProbe)
    awaitConfirmation(confirmProbe)

    system.stop(processor)
  }

  "A processor that uses a channel" can {
    "forward new messages to destination" in {
      processor ! Persistent("a2")
      expectMsgPF() { case m @ ConfirmablePersistent("fw: a2", _, _) ⇒ m.confirm() }
    }
    "reply new messages to senders" in {
      processor ! Persistent("b2")
      expectMsgPF() { case m @ ConfirmablePersistent("re: b2", _, _) ⇒ m.confirm() }
    }
    "resend unconfirmed messages on restart" in {
      val probe = TestProbe()
      val p = system.actorOf(Props(classOf[ResendingProcessor], "rp", probe.ref))

      p ! Persistent("a")

      probe.expectMsgPF() { case cp @ ConfirmablePersistent("a", 1L, 0) ⇒ }
      probe.expectMsgPF() { case cp @ ConfirmablePersistent("a", 1L, 1) ⇒ }
      probe.expectNoMsg(200 milliseconds)

      p ! "replay"

      probe.expectMsgPF() { case cp @ ConfirmablePersistent("a", 1L, 0) ⇒ }
      probe.expectMsgPF() { case cp @ ConfirmablePersistent("a", 1L, 1) ⇒ cp.confirm() }
    }
  }

  "An eventsourced processor that uses a channel" can {
    "reliably deliver events" in {
      val probe = TestProbe()
      val ep = system.actorOf(Props(classOf[ResendingEventsourcedProcessor], "rep", probe.ref))

      ep ! "cmd"

      probe.expectMsgPF() { case cp @ ConfirmablePersistent("evt", 1L, 0) ⇒ }
      probe.expectMsgPF() { case cp @ ConfirmablePersistent("evt", 1L, 1) ⇒ }
      probe.expectNoMsg(200 milliseconds)

      ep ! "replay"

      probe.expectMsgPF() { case cp @ ConfirmablePersistent("evt", 1L, 0) ⇒ }
      probe.expectMsgPF() { case cp @ ConfirmablePersistent("evt", 1L, 1) ⇒ cp.confirm() }
    }
  }
}

class LeveldbProcessorChannelSpec extends ProcessorChannelSpec(PersistenceSpec.config("leveldb", "LeveldbProcessorChannelSpec"))
class InmemProcessorChannelSpec extends ProcessorChannelSpec(PersistenceSpec.config("inmem", "InmemProcessorChannelSpec"))

