/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import scala.concurrent.duration._
import scala.language.postfixOps

import com.typesafe.config._

import akka.actor._
import akka.testkit._

import akka.persistence.JournalProtocol.Confirm

object ChannelSpec {
  class TestDestination extends Actor {
    def receive = {
      case m: ConfirmablePersistent ⇒ sender ! m
    }
  }

  class TestDestinationProcessor(name: String) extends NamedProcessor(name) {
    def receive = {
      case cp @ ConfirmablePersistent("a", _, _)                          ⇒ cp.confirm()
      case cp @ ConfirmablePersistent("b", _, _)                          ⇒ cp.confirm()
      case cp @ ConfirmablePersistent("boom", _, _) if (recoveryFinished) ⇒ throw new TestException("boom")
    }
  }

  class TestReceiver(testActor: ActorRef) extends Actor {
    def receive = {
      case cp @ ConfirmablePersistent(payload, _, _) ⇒
        testActor ! payload
        cp.confirm()
    }
  }
}

abstract class ChannelSpec(config: Config) extends AkkaSpec(config) with PersistenceSpec with ImplicitSender {
  import ChannelSpec._

  protected var defaultTestChannel: ActorRef = _
  protected var redeliverTestChannel: ActorRef = _

  override protected def beforeEach: Unit = {
    super.beforeEach()
    defaultTestChannel = createDefaultTestChannel()
    redeliverTestChannel = createRedeliverTestChannel()
  }

  override protected def afterEach(): Unit = {
    system.stop(defaultTestChannel)
    system.stop(redeliverTestChannel)
    super.afterEach()
  }

  def redeliverChannelSettings: ChannelSettings =
    ChannelSettings(redeliverMax = 2, redeliverInterval = 100 milliseconds)

  def createDefaultTestChannel(): ActorRef =
    system.actorOf(Channel.props(s"${name}-default", ChannelSettings()))

  def createRedeliverTestChannel(): ActorRef =
    system.actorOf(Channel.props(s"${name}-redeliver", redeliverChannelSettings))

  def subscribeToConfirmation(probe: TestProbe): Unit =
    system.eventStream.subscribe(probe.ref, classOf[Confirm])

  def awaitConfirmation(probe: TestProbe): Unit =
    probe.expectMsgType[Confirm]

  def actorRefFor(topLevelName: String) =
    extension.system.provider.resolveActorRef(RootActorPath(Address("akka", system.name)) / "user" / topLevelName)

  "A channel" must {
    "must resolve sender references and preserve message order" in {
      val destination = system.actorOf(Props[TestDestination])

      val empty = actorRefFor("testSender") // will be an EmptyLocalActorRef
      val sender = system.actorOf(Props(classOf[TestReceiver], testActor), "testSender")

      // replayed message (resolved = false) and invalid sender reference
      defaultTestChannel tell (Deliver(PersistentRepr("a", resolved = false), destination, Resolve.Sender), empty)

      // new messages (resolved = true) and valid sender references
      defaultTestChannel tell (Deliver(Persistent("b"), destination), sender)
      defaultTestChannel tell (Deliver(Persistent("c"), destination), sender)

      expectMsg("a")
      expectMsg("b")
      expectMsg("c")
    }
    "must resolve destination references and preserve message order" in {
      val empty = actorRefFor("testDestination") // will be an EmptyLocalActorRef
      val destination = system.actorOf(Props(classOf[TestReceiver], testActor), "testDestination")

      // replayed message (resolved = false) and invalid destination reference
      defaultTestChannel ! Deliver(PersistentRepr("a", resolved = false), empty, Resolve.Destination)

      // new messages (resolved = true) and valid destination references
      defaultTestChannel ! Deliver(Persistent("b"), destination)
      defaultTestChannel ! Deliver(Persistent("c"), destination)

      expectMsg("a")
      expectMsg("b")
      expectMsg("c")
    }
    "support processors as destination" in {
      val destination = system.actorOf(Props(classOf[TestDestinationProcessor], name))
      val confirmProbe = TestProbe()

      subscribeToConfirmation(confirmProbe)

      defaultTestChannel ! Deliver(Persistent("a"), destination)

      awaitConfirmation(confirmProbe)
    }
    "support processors as destination that may fail" in {
      val destination = system.actorOf(Props(classOf[TestDestinationProcessor], name))
      val confirmProbe = TestProbe()

      subscribeToConfirmation(confirmProbe)

      defaultTestChannel ! Deliver(Persistent("a"), destination)
      defaultTestChannel ! Deliver(Persistent("boom"), destination)
      defaultTestChannel ! Deliver(Persistent("b"), destination)

      awaitConfirmation(confirmProbe)
      awaitConfirmation(confirmProbe)
    }
    "accept confirmable persistent messages for delivery" in {
      val destination = system.actorOf(Props[TestDestination])
      val confirmProbe = TestProbe()

      subscribeToConfirmation(confirmProbe)

      defaultTestChannel ! Deliver(PersistentRepr("a", confirmable = true), destination)

      expectMsgPF() { case m @ ConfirmablePersistent("a", _, _) ⇒ m.confirm() }
      awaitConfirmation(confirmProbe)
    }
    "redeliver on missing confirmation" in {
      val probe = TestProbe()

      redeliverTestChannel ! Deliver(Persistent("b"), probe.ref)

      probe.expectMsgPF() { case m @ ConfirmablePersistent("b", _, redeliveries) ⇒ redeliveries should be(0) }
      probe.expectMsgPF() { case m @ ConfirmablePersistent("b", _, redeliveries) ⇒ redeliveries should be(1) }
      probe.expectMsgPF() { case m @ ConfirmablePersistent("b", _, redeliveries) ⇒ redeliveries should be(2); m.confirm() }
    }
    "redeliver in correct relative order" in {
      val deliveries = redeliverChannelSettings.redeliverMax + 1
      val interval = redeliverChannelSettings.redeliverInterval.toMillis / 5 * 4

      val probe = TestProbe()
      val cycles = 9

      1 to cycles foreach { i ⇒
        redeliverTestChannel ! Deliver(Persistent(i), probe.ref)
        Thread.sleep(interval)
      }

      val received = (1 to (cycles * deliveries)).foldLeft(Vector.empty[ConfirmablePersistent]) {
        case (acc, _) ⇒ acc :+ probe.expectMsgType[ConfirmablePersistent]
      }

      val grouped = received.groupBy(_.redeliveries)
      val expected = 1 to 9 toVector

      grouped(0).map(_.payload) should be(expected)
      grouped(1).map(_.payload) should be(expected)
      grouped(2).map(_.payload) should be(expected)
    }
    "redeliver not more than redeliverMax on missing confirmation" in {
      val probe = TestProbe()

      redeliverTestChannel ! Deliver(PersistentRepr("a"), probe.ref)

      probe.expectMsgPF() { case m @ ConfirmablePersistent("a", _, redeliveries) ⇒ redeliveries should be(0) }
      probe.expectMsgPF() { case m @ ConfirmablePersistent("a", _, redeliveries) ⇒ redeliveries should be(1) }
      probe.expectMsgPF() { case m @ ConfirmablePersistent("a", _, redeliveries) ⇒ redeliveries should be(2) }
      probe.expectNoMsg(300 milliseconds)
    }
  }
}

class LeveldbChannelSpec extends ChannelSpec(PersistenceSpec.config("leveldb", "LeveldbChannelSpec"))
class InmemChannelSpec extends ChannelSpec(PersistenceSpec.config("inmem", "InmemChannelSpec"))

