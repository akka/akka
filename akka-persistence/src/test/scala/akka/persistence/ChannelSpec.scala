/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import com.typesafe.config._

import akka.actor._
import akka.testkit._

object ChannelSpec {
  class TestProcessor(name: String, channelProps: Props) extends NamedProcessor(name) {
    val destination = context.actorOf(Props[TestDestination])
    val channel = context.actorOf(channelProps)

    def receive = {
      case m @ Persistent(s: String, _) if s.startsWith("a") ⇒ {
        // forward to destination via channel,
        // destination replies to initial sender
        channel forward Deliver(m.withPayload(s"fw: ${s}"), destination)
      }
      case m @ Persistent(s: String, _) if s.startsWith("b") ⇒ {
        // reply to sender via channel
        channel ! Deliver(m.withPayload(s"re: ${s}"), sender)
      }
    }
  }

  class TestDestination extends Actor {
    def receive = {
      case m: Persistent ⇒ sender ! m
    }
  }

  class TestReceiver(testActor: ActorRef) extends Actor {
    def receive = {
      case Persistent(payload, _) ⇒ testActor ! payload
    }
  }

  class TestDestinationProcessor(name: String) extends NamedProcessor(name) {
    def receive = {
      case cp @ ConfirmablePersistent("a", _)                          ⇒ cp.confirm()
      case cp @ ConfirmablePersistent("b", _)                          ⇒ cp.confirm()
      case cp @ ConfirmablePersistent("boom", _) if (recoveryFinished) ⇒ throw new TestException("boom")
    }
  }
}

abstract class ChannelSpec(config: Config) extends AkkaSpec(config) with PersistenceSpec with ImplicitSender {
  import ChannelSpec._

  override protected def beforeEach() {
    super.beforeEach()

    val confirmProbe = TestProbe()
    val forwardProbe = TestProbe()
    val replyProbe = TestProbe()

    val processor = system.actorOf(Props(classOf[TestProcessor], name, channelProps(s"${name}-channel")))

    subscribeToConfirmation(confirmProbe)

    processor tell (Persistent("a1"), forwardProbe.ref)
    processor tell (Persistent("b1"), replyProbe.ref)

    forwardProbe.expectMsgPF() { case m @ ConfirmablePersistent("fw: a1", _) ⇒ m.confirm() }
    replyProbe.expectMsgPF() { case m @ ConfirmablePersistent("re: b1", _) ⇒ m.confirm() }

    awaitConfirmation(confirmProbe)
    awaitConfirmation(confirmProbe)
  }

  def actorRefFor(topLevelName: String) =
    extension.system.provider.resolveActorRef(RootActorPath(Address("akka", system.name)) / "user" / topLevelName)

  def channelProps(channelId: String): Props =
    Channel.props(channelId)

  def subscribeToConfirmation(probe: TestProbe): Unit =
    system.eventStream.subscribe(probe.ref, classOf[Confirm])

  def awaitConfirmation(probe: TestProbe): Unit =
    probe.expectMsgType[Confirm]

  "A channel" must {
    "forward new messages to destination" in {
      val processor = system.actorOf(Props(classOf[TestProcessor], name, channelProps(s"${name}-channel")))
      processor ! Persistent("a2")
      expectMsgPF() { case m @ ConfirmablePersistent("fw: a2", _) ⇒ m.confirm() }
    }
    "reply new messages to senders" in {
      val processor = system.actorOf(Props(classOf[TestProcessor], name, channelProps(s"${name}-channel")))
      processor ! Persistent("b2")
      expectMsgPF() { case m @ ConfirmablePersistent("re: b2", _) ⇒ m.confirm() }
    }
    "forward un-confirmed stored messages to destination during recovery" in {
      val confirmProbe = TestProbe()
      val forwardProbe = TestProbe()

      subscribeToConfirmation(confirmProbe)

      val processor1 = system.actorOf(Props(classOf[TestProcessor], name, channelProps(s"${name}-channel")))

      processor1 tell (Persistent("a1"), forwardProbe.ref)
      processor1 tell (Persistent("a2"), forwardProbe.ref)

      forwardProbe.expectMsgPF() { case m @ ConfirmablePersistent("fw: a1", _) ⇒ /* no confirmation */ }
      forwardProbe.expectMsgPF() { case m @ ConfirmablePersistent("fw: a2", _) ⇒ m.confirm() }

      awaitConfirmation(confirmProbe)

      val processor2 = system.actorOf(Props(classOf[TestProcessor], name, channelProps(s"${name}-channel")))

      processor2 tell (Persistent("a3"), forwardProbe.ref)

      forwardProbe.expectMsgPF() { case m @ ConfirmablePersistent("fw: a1", _) ⇒ m.confirm() } // sender still valid, no need to resolve
      forwardProbe.expectMsgPF() { case m @ ConfirmablePersistent("fw: a3", _) ⇒ m.confirm() }

      awaitConfirmation(confirmProbe)
      awaitConfirmation(confirmProbe)
    }
    "must resolve sender references and preserve message order" in {
      val channel = system.actorOf(channelProps("channel-1"))
      val destination = system.actorOf(Props[TestDestination])

      val empty = actorRefFor("testSender") // will be an EmptyLocalActorRef
      val sender = system.actorOf(Props(classOf[TestReceiver], testActor), "testSender")

      // replayed message (resolved = false) and invalid sender reference
      channel tell (Deliver(PersistentRepr("a", resolved = false), destination, Resolve.Sender), empty)

      // new messages (resolved = true) and valid sender references
      channel tell (Deliver(Persistent("b"), destination), sender)
      channel tell (Deliver(Persistent("c"), destination), sender)

      expectMsg("a")
      expectMsg("b")
      expectMsg("c")
    }
    "must resolve destination references and preserve message order" in {
      val channel = system.actorOf(channelProps("channel-2"))

      val empty = actorRefFor("testDestination") // will be an EmptyLocalActorRef
      val destination = system.actorOf(Props(classOf[TestReceiver], testActor), "testDestination")

      // replayed message (resolved = false) and invalid destination reference
      channel ! Deliver(PersistentRepr("a", resolved = false), empty, Resolve.Destination)

      // new messages (resolved = true) and valid destination references
      channel ! Deliver(Persistent("b"), destination)
      channel ! Deliver(Persistent("c"), destination)

      expectMsg("a")
      expectMsg("b")
      expectMsg("c")
    }
    "support processors as destination" in {
      val channel = system.actorOf(channelProps(s"${name}-channel-new"))
      val destination = system.actorOf(Props(classOf[TestDestinationProcessor], s"${name}-new"))
      val confirmProbe = TestProbe()

      subscribeToConfirmation(confirmProbe)

      channel ! Deliver(Persistent("a"), destination)

      awaitConfirmation(confirmProbe)
    }
    "support processors as destination that may fail" in {
      val channel = system.actorOf(channelProps(s"${name}-channel-new"))
      val destination = system.actorOf(Props(classOf[TestDestinationProcessor], s"${name}-new"))
      val confirmProbe = TestProbe()

      subscribeToConfirmation(confirmProbe)

      channel ! Deliver(Persistent("a"), destination)
      channel ! Deliver(Persistent("boom"), destination)
      channel ! Deliver(Persistent("b"), destination)

      awaitConfirmation(confirmProbe)
      awaitConfirmation(confirmProbe)
    }
    "accept confirmable persistent messages for delivery" in {
      val channel = system.actorOf(channelProps(s"${name}-channel-new"))
      val destination = system.actorOf(Props[TestDestination])
      val confirmProbe = TestProbe()

      subscribeToConfirmation(confirmProbe)

      channel ! Deliver(PersistentRepr("a", confirmable = true), destination)

      expectMsgPF() { case m @ ConfirmablePersistent("a", _) ⇒ m.confirm() }
      awaitConfirmation(confirmProbe)
    }
  }
}

abstract class PersistentChannelSpec(config: Config) extends ChannelSpec(config) {
  override def channelProps(channelId: String): Props =
    PersistentChannel.props(channelId)

  override def subscribeToConfirmation(probe: TestProbe): Unit =
    system.eventStream.subscribe(probe.ref, classOf[JournalProtocol.Delete])

  override def awaitConfirmation(probe: TestProbe): Unit =
    probe.expectMsgType[JournalProtocol.Delete]

  "A persistent channel" must {
    "support disabling and re-enabling delivery" in {
      val channel = system.actorOf(channelProps(s"${name}-channel"))
      val confirmProbe = TestProbe()

      subscribeToConfirmation(confirmProbe)

      channel ! Deliver(Persistent("a"), testActor)

      expectMsgPF() { case m @ ConfirmablePersistent("a", _) ⇒ m.confirm() }
      awaitConfirmation(confirmProbe)

      channel ! DisableDelivery
      channel ! Deliver(Persistent("b"), testActor)
      channel ! EnableDelivery
      channel ! Deliver(Persistent("c"), testActor)

      expectMsgPF() { case m @ ConfirmablePersistent("b", _) ⇒ m.confirm() }
      expectMsgPF() { case m @ ConfirmablePersistent("c", _) ⇒ m.confirm() }
    }
    "support Persistent replies to Deliver senders" in {
      val channel = system.actorOf(PersistentChannel.props(s"${name}-channel-new", true))

      channel ! Deliver(Persistent("a"), system.deadLetters)
      expectMsgPF() { case Persistent("a", 1) ⇒ }

      channel ! Deliver(PersistentRepr("b", sequenceNr = 13), system.deadLetters)
      expectMsgPF() { case Persistent("b", 13) ⇒ }
    }
    "must not modify certain persistent message field" in {
      val channel = system.actorOf(channelProps(s"${name}-channel-new"))
      val persistent1 = PersistentRepr(payload = "a", processorId = "p1", confirms = List("c1", "c2"), sender = channel, sequenceNr = 13)
      val persistent2 = PersistentRepr(payload = "b", processorId = "p1", confirms = List("c1", "c2"), sender = channel)

      channel ! Deliver(persistent1, testActor)
      channel ! Deliver(persistent2, testActor)

      expectMsgPF() { case ConfirmablePersistentImpl("a", 13, "p1", _, _, Seq("c1", "c2"), _, _, channel) ⇒ }
      expectMsgPF() { case ConfirmablePersistentImpl("b", 2, "p1", _, _, Seq("c1", "c2"), _, _, channel) ⇒ }
    }
  }

  "A persistent channel" when {
    "used standalone" must {
      "redeliver un-confirmed stored messages during recovery" in {
        val confirmProbe = TestProbe()
        val forwardProbe = TestProbe()

        subscribeToConfirmation(confirmProbe)

        val channel1 = system.actorOf(channelProps(s"${name}-channel"))
        channel1 tell (Deliver(Persistent("a1"), forwardProbe.ref), null)
        channel1 tell (Deliver(Persistent("a2"), forwardProbe.ref), null)

        forwardProbe.expectMsgPF() { case m @ ConfirmablePersistent("a1", _) ⇒ /* no confirmation */ }
        forwardProbe.expectMsgPF() { case m @ ConfirmablePersistent("a2", _) ⇒ m.confirm() }

        awaitConfirmation(confirmProbe)

        val channel2 = system.actorOf(channelProps(s"${name}-channel"))
        channel2 tell (Deliver(Persistent("a3"), forwardProbe.ref), null)

        forwardProbe.expectMsgPF() { case m @ ConfirmablePersistent("a1", _) ⇒ m.confirm() } // sender still valid, no need to resolve
        forwardProbe.expectMsgPF() { case m @ ConfirmablePersistent("a3", _) ⇒ m.confirm() }

        awaitConfirmation(confirmProbe)
        awaitConfirmation(confirmProbe)
      }
    }
  }
}

class LeveldbChannelSpec extends ChannelSpec(PersistenceSpec.config("leveldb", "channel"))
class InmemChannelSpec extends ChannelSpec(PersistenceSpec.config("inmem", "channel"))

class LeveldbPersistentChannelSpec extends PersistentChannelSpec(PersistenceSpec.config("leveldb", "persistent-channel"))
class InmemPersistentChannelSpec extends PersistentChannelSpec(PersistenceSpec.config("inmem", "persistent-channel"))
