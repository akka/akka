/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import scala.concurrent.duration._
import scala.language.postfixOps

import com.typesafe.config._

import akka.actor._
import akka.testkit._

object ProcessorChannelSpec {
  class TestProcessor(name: String, channelProps: Props) extends NamedProcessor(name) {
    val destination = context.actorOf(Props[TestDestination])
    val channel = context.actorOf(channelProps)

    def receive = {
      case m @ Persistent(s: String, _) if s.startsWith("a") ⇒
        // forward to destination via channel,
        // destination replies to initial sender
        channel forward Deliver(m.withPayload(s"fw: ${s}"), destination.path)
      case m @ Persistent(s: String, _) if s.startsWith("b") ⇒
        // reply to sender via channel
        channel ! Deliver(m.withPayload(s"re: ${s}"), sender().path)
      case m @ Persistent(s: String, _) if s.startsWith("c") ⇒
        // don't use channel
        sender() ! s"got: ${s}"
      case "replay" ⇒ throw new TestException("replay requested")
    }
  }

  class TestDestination extends Actor {
    def receive = {
      case m: Persistent ⇒ sender() ! m
    }
  }

  class ResendingProcessor(name: String, channelProps: Props, destination: ActorRef) extends NamedProcessor(name) {
    val channel = context.actorOf(channelProps)

    def receive = {
      case p: Persistent ⇒ channel ! Deliver(p, destination.path)
      case "replay"      ⇒ throw new TestException("replay requested")
    }
  }

  class ResendingPersistentActor(name: String, channelProps: Props, destination: ActorRef) extends NamedProcessor(name) with PersistentActor {
    val channel = context.actorOf(channelProps)

    var events: List[String] = Nil

    def handleEvent(event: String) = {
      events = event :: events
      channel ! Deliver(Persistent(event), destination.path)
    }

    def receiveRecover: Receive = {
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

  override protected def beforeEach(): Unit = {
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
    system.actorOf(Props(classOf[TestProcessor], name, testChannelProps))

  def testChannelProps: Props

  def testResendingChannelProps: Props

  def setupTestProcessorData(): Unit = {
    val confirmProbe = TestProbe()
    val forwardProbe = TestProbe()
    val replyProbe = TestProbe()
    val senderProbe = TestProbe()

    val processor = createTestProcessor()

    subscribeToConfirmation(confirmProbe)

    processor tell (Persistent("a1"), forwardProbe.ref)
    processor tell (Persistent("b1"), replyProbe.ref)
    processor tell (Persistent("c1"), senderProbe.ref)

    forwardProbe.expectMsgPF() { case m @ ConfirmablePersistent("fw: a1", _, _) ⇒ m.confirm() }
    replyProbe.expectMsgPF() { case m @ ConfirmablePersistent("re: b1", _, _) ⇒ m.confirm() }
    senderProbe.expectMsg("got: c1")

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
    "de-duplicate confirmed messages on restart" in {
      processor ! Persistent("c3")
      expectMsg("got: c3")
      processor ! Persistent("a3")
      expectMsgPF() { case m @ ConfirmablePersistent("fw: a3", _, _) ⇒ m.confirm() }

      processor ! "replay"
      expectMsg("got: c3")
      expectNoMsg(1.second)
    }
    "de-duplicate confirmed messages on starting new with same processor id" in {
      processor ! Persistent("c4")
      expectMsg("got: c4")
      processor ! Persistent("a4")
      expectMsgPF() { case m @ ConfirmablePersistent("fw: a4", _, _) ⇒ m.confirm() }

      val p2 = createTestProcessor()
      expectMsg("got: c4")
      expectNoMsg(1.second)
    }
    "resend unconfirmed messages on restart" in {
      val probe = TestProbe()
      val p = system.actorOf(Props(classOf[ResendingProcessor], "rp", testResendingChannelProps, probe.ref))

      p ! Persistent("a")

      probe.expectMsgPF() { case cp @ ConfirmablePersistent("a", 1L, 0) ⇒ }
      probe.expectMsgPF() { case cp @ ConfirmablePersistent("a", 1L, 1) ⇒ }
      probe.expectNoMsg(200 milliseconds)

      p ! "replay"

      probe.expectMsgPF() { case cp @ ConfirmablePersistent("a", 1L, 0) ⇒ }
      probe.expectMsgPF() { case cp @ ConfirmablePersistent("a", 1L, 1) ⇒ cp.confirm() }
    }
  }

  "A persistent actor that uses a channel" can {
    "reliably deliver events" in {
      val probe = TestProbe()
      val ep = system.actorOf(Props(classOf[ResendingPersistentActor], "rep", testResendingChannelProps, probe.ref))

      ep ! "cmd"

      probe.expectMsgPF() { case cp @ ConfirmablePersistent("evt", _, 0) ⇒ }
      probe.expectMsgPF() { case cp @ ConfirmablePersistent("evt", _, 1) ⇒ }
      probe.expectNoMsg(200 milliseconds)

      ep ! "replay"

      probe.expectMsgPF() { case cp @ ConfirmablePersistent("evt", _, 0) ⇒ }
      probe.expectMsgPF() { case cp @ ConfirmablePersistent("evt", _, 1) ⇒ cp.confirm() }
    }
  }
}

class LeveldbProcessorChannelSpec extends ProcessorChannelSpec(PersistenceSpec.config("leveldb", "LeveldbProcessorChannelSpec")) {
  def testChannelProps: Props = Channel.props(s"${name}-channel")
  def testResendingChannelProps: Props =
    Channel.props("channel", ChannelSettings(redeliverMax = 1, redeliverInterval = 100 milliseconds))
}
class InmemProcessorChannelSpec extends ProcessorChannelSpec(PersistenceSpec.config("inmem", "InmemProcessorChannelSpec")) {
  def testChannelProps: Props = Channel.props(s"${name}-channel")
  def testResendingChannelProps: Props =
    Channel.props("channel", ChannelSettings(redeliverMax = 1, redeliverInterval = 100 milliseconds))
}

class LeveldbProcessorPersistentChannelSpec extends ProcessorChannelSpec(PersistenceSpec.config("leveldb", "LeveldbProcessorPersistentChannelSpec")) {
  def testChannelProps: Props = PersistentChannel.props(s"${name}-channel")
  def testResendingChannelProps: Props =
    PersistentChannel.props("channel", PersistentChannelSettings(redeliverMax = 1, redeliverInterval = 100 milliseconds))
}
class InmemProcessorPersistentChannelSpec extends ProcessorChannelSpec(PersistenceSpec.config("inmem", "InmemProcessorPersistentChannelSpec")) {
  def testChannelProps: Props = PersistentChannel.props(s"${name}-channel")
  def testResendingChannelProps: Props =
    PersistentChannel.props("channel", PersistentChannelSettings(redeliverMax = 1, redeliverInterval = 100 milliseconds))
}

