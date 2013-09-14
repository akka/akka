package akka.persistence

import akka.actor._
import akka.testkit._

object ChannelSpec {
  val config =
    """
      |serialize-creators = on
      |serialize-messages = on
      |akka.persistence.journal.leveldb.dir = "target/journal-channel-spec"
    """.stripMargin

  class TestProcessor extends Processor {
    val destination = context.actorOf(Props[TestDestination])
    val channel = context.actorOf(Channel.props(), "channel")

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

class ChannelSpec extends AkkaSpec(ChannelSpec.config) with PersistenceSpec with ImplicitSender {
  import ChannelSpec._

  override protected def beforeEach() {
    super.beforeEach()

    val confirmProbe = TestProbe()
    val forwardProbe = TestProbe()
    val replyProbe = TestProbe()

    val processor = system.actorOf(Props[TestProcessor], name)

    system.eventStream.subscribe(confirmProbe.ref, classOf[Confirm])

    processor tell (Persistent("a1"), forwardProbe.ref)
    processor tell (Persistent("b1"), replyProbe.ref)

    forwardProbe.expectMsgPF() { case m @ Persistent("fw: a1", _) ⇒ m.confirm() }
    replyProbe.expectMsgPF() { case m @ Persistent("re: b1", _) ⇒ m.confirm() }

    // wait for confirmations to be stored by journal (needed
    // for replay so that channels can drop confirmed messages)
    confirmProbe.expectMsgType[Confirm]
    confirmProbe.expectMsgType[Confirm]

    stopAndAwaitTermination(processor)
  }

  "A channel" must {
    "forward un-confirmed messages to destination" in {
      val processor = system.actorOf(Props[TestProcessor], name)
      processor ! Persistent("a2")
      expectMsgPF() { case m @ Persistent("fw: a2", _) ⇒ m.confirm() }
    }
    "reply un-confirmed messages to senders" in {
      val processor = system.actorOf(Props[TestProcessor], name)
      processor ! Persistent("b2")
      expectMsgPF() { case m @ Persistent("re: b2", _) ⇒ m.confirm() }
    }
    "must resolve sender references and preserve message order" in {
      val channel = system.actorOf(Channel.props(), "testChannel1")
      val destination = system.actorOf(Props[TestDestination])
      val sender1 = system.actorOf(Props(classOf[TestReceiver], testActor), "testSender")

      channel tell (Deliver(Persistent("a"), destination), sender1)
      expectMsg("a")
      stopAndAwaitTermination(sender1)

      // create new incarnation of sender (with same actor path)
      val sender2 = system.actorOf(Props(classOf[TestReceiver], testActor), "testSender")

      // replayed message (resolved = false) and invalid sender reference
      channel tell (Deliver(PersistentImpl("a", resolved = false), destination, Resolve.Sender), sender1)

      // new messages (resolved = true) and valid sender references
      channel tell (Deliver(Persistent("b"), destination), sender2)
      channel tell (Deliver(Persistent("c"), destination), sender2)

      expectMsg("a")
      expectMsg("b")
      expectMsg("c")
    }
    "must resolve destination references and preserve message order" in {
      val channel = system.actorOf(Channel.props(), "testChannel2")
      val destination1 = system.actorOf(Props(classOf[TestReceiver], testActor), "testDestination")

      channel ! Deliver(Persistent("a"), destination1)
      expectMsg("a")
      stopAndAwaitTermination(destination1)

      // create new incarnation of destination (with same actor path)
      val destination2 = system.actorOf(Props(classOf[TestReceiver], testActor), "testDestination")

      // replayed message (resolved = false) and invalid destination reference
      channel ! Deliver(PersistentImpl("a", resolved = false), destination1, Resolve.Destination)

      // new messages (resolved = true) and valid destination references
      channel ! Deliver(Persistent("b"), destination2)
      channel ! Deliver(Persistent("c"), destination2)

      expectMsg("a")
      expectMsg("b")
      expectMsg("c")
    }
  }
}
