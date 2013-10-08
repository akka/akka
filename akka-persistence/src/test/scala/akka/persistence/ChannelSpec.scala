/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import com.typesafe.config._

import akka.actor._
import akka.testkit._

object ChannelSpec {
  class TestProcessor(name: String) extends NamedProcessor(name) {
    val destination = context.actorOf(Props[TestDestination])
    val channel = context.actorOf(Channel.props("channel"))

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
}

abstract class ChannelSpec(config: Config) extends AkkaSpec(config) with PersistenceSpec with ImplicitSender {
  import ChannelSpec._

  override protected def beforeEach() {
    super.beforeEach()

    val confirmProbe = TestProbe()
    val forwardProbe = TestProbe()
    val replyProbe = TestProbe()

    val processor = system.actorOf(Props(classOf[TestProcessor], name))

    system.eventStream.subscribe(confirmProbe.ref, classOf[Confirm])

    processor tell (Persistent("a1"), forwardProbe.ref)
    processor tell (Persistent("b1"), replyProbe.ref)

    forwardProbe.expectMsgPF() { case m @ Persistent("fw: a1", _) ⇒ m.confirm() }
    replyProbe.expectMsgPF() { case m @ Persistent("re: b1", _) ⇒ m.confirm() }

    // wait for confirmations to be stored by journal (needed
    // for replay so that channels can drop confirmed messages)
    confirmProbe.expectMsgType[Confirm]
    confirmProbe.expectMsgType[Confirm]
  }

  def actorRefFor(topLevelName: String) = {
    extension.system.provider.resolveActorRef(RootActorPath(Address("akka", system.name)) / "user" / topLevelName)
  }

  "A channel" must {
    "forward un-confirmed messages to destination" in {
      val processor = system.actorOf(Props(classOf[TestProcessor], name))
      processor ! Persistent("a2")
      expectMsgPF() { case m @ Persistent("fw: a2", _) ⇒ m.confirm() }
    }
    "reply un-confirmed messages to senders" in {
      val processor = system.actorOf(Props(classOf[TestProcessor], name))
      processor ! Persistent("b2")
      expectMsgPF() { case m @ Persistent("re: b2", _) ⇒ m.confirm() }
    }
    "must resolve sender references and preserve message order" in {
      val channel = system.actorOf(Channel.props())
      val destination = system.actorOf(Props[TestDestination])

      val empty = actorRefFor("testSender") // will be an EmptyLocalActorRef
      val sender = system.actorOf(Props(classOf[TestReceiver], testActor), "testSender")

      // replayed message (resolved = false) and invalid sender reference
      channel tell (Deliver(PersistentImpl("a", resolved = false), destination, Resolve.Sender), empty)

      // new messages (resolved = true) and valid sender references
      channel tell (Deliver(Persistent("b"), destination), sender)
      channel tell (Deliver(Persistent("c"), destination), sender)

      expectMsg("a")
      expectMsg("b")
      expectMsg("c")
    }
    "must resolve destination references and preserve message order" in {
      val channel = system.actorOf(Channel.props())

      val empty = actorRefFor("testDestination") // will be an EmptyLocalActorRef
      val destination = system.actorOf(Props(classOf[TestReceiver], testActor), "testDestination")

      // replayed message (resolved = false) and invalid destination reference
      channel ! Deliver(PersistentImpl("a", resolved = false), empty, Resolve.Destination)

      // new messages (resolved = true) and valid destination references
      channel ! Deliver(Persistent("b"), destination)
      channel ! Deliver(Persistent("c"), destination)

      expectMsg("a")
      expectMsg("b")
      expectMsg("c")
    }
  }
}

class LeveldbChannelSpec extends ChannelSpec(PersistenceSpec.config("leveldb", "channel"))
class InmemChannelSpec extends ChannelSpec(PersistenceSpec.config("inmem", "channel"))
