/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import scala.concurrent.duration._
import scala.language.postfixOps

import com.typesafe.config._

import akka.actor._
import akka.testkit._

object ChannelSpec {
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

  class TestListener(probe: ActorRef) extends Actor {
    def receive = {
      case RedeliverFailure(messages) ⇒ messages.foreach(probe ! _.payload)
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

  private def redeliverChannelSettings(listener: Option[ActorRef]): ChannelSettings =
    ChannelSettings(redeliverMax = 2, redeliverInterval = 100 milliseconds, redeliverFailureListener = listener)

  def createDefaultTestChannel(): ActorRef =
    system.actorOf(Channel.props(s"${name}-default", ChannelSettings()))

  def createRedeliverTestChannel(): ActorRef =
    system.actorOf(Channel.props(s"${name}-redeliver", redeliverChannelSettings(None)))

  def createRedeliverTestChannel(listener: Option[ActorRef]): ActorRef =
    system.actorOf(Channel.props(s"${name}-redeliver-listener", redeliverChannelSettings(listener)))

  def subscribeToConfirmation(probe: TestProbe): Unit =
    system.eventStream.subscribe(probe.ref, classOf[Delivered])

  def awaitConfirmation(probe: TestProbe): Unit =
    probe.expectMsgType[Delivered]

  def actorRefFor(topLevelName: String) =
    extension.system.provider.resolveActorRef(RootActorPath(Address("akka", system.name)) / "user" / topLevelName)

  "A channel" must {
    "must resolve destination references and preserve message order" in {
      val empty = actorRefFor("testDestination") // will be an EmptyLocalActorRef
      val probe = TestProbe()
      val destination = system.actorOf(Props(classOf[TestReceiver], probe.ref), "testDestination")

      defaultTestChannel ! Deliver(PersistentRepr("a"), empty.path)
      defaultTestChannel ! Deliver(Persistent("b"), destination.path)
      defaultTestChannel ! Deliver(Persistent("c"), destination.path)

      probe.expectMsg("a")
      probe.expectMsg("b")
      probe.expectMsg("c")
    }
    "support processors as destination" in {
      val destination = system.actorOf(Props(classOf[TestDestinationProcessor], name))
      val confirmProbe = TestProbe()

      subscribeToConfirmation(confirmProbe)

      defaultTestChannel ! Deliver(Persistent("a"), destination.path)

      awaitConfirmation(confirmProbe)
    }
    "support processors as destination that may fail" in {
      val destination = system.actorOf(Props(classOf[TestDestinationProcessor], name))
      val confirmProbe = TestProbe()

      subscribeToConfirmation(confirmProbe)

      defaultTestChannel ! Deliver(Persistent("a"), destination.path)
      defaultTestChannel ! Deliver(Persistent("boom"), destination.path)
      defaultTestChannel ! Deliver(Persistent("b"), destination.path)

      awaitConfirmation(confirmProbe)
      awaitConfirmation(confirmProbe)
    }
    "accept confirmable persistent messages for delivery" in {
      val confirmProbe = TestProbe()
      val destinationProbe = TestProbe()

      subscribeToConfirmation(confirmProbe)

      defaultTestChannel ! Deliver(PersistentRepr("a", confirmable = true), destinationProbe.ref.path)

      destinationProbe.expectMsgPF() { case m @ ConfirmablePersistent("a", _, _) ⇒ m.confirm() }
      awaitConfirmation(confirmProbe)
    }
    "redeliver on missing confirmation" in {
      val probe = TestProbe()

      redeliverTestChannel ! Deliver(Persistent("b"), probe.ref.path)

      probe.expectMsgPF() { case m @ ConfirmablePersistent("b", _, redeliveries) ⇒ redeliveries should be(0) }
      probe.expectMsgPF() { case m @ ConfirmablePersistent("b", _, redeliveries) ⇒ redeliveries should be(1) }
      probe.expectMsgPF() { case m @ ConfirmablePersistent("b", _, redeliveries) ⇒ redeliveries should be(2); m.confirm() }
    }
    "redeliver in correct relative order" in {
      val deliveries = redeliverChannelSettings(None).redeliverMax + 1
      val interval = redeliverChannelSettings(None).redeliverInterval.toMillis / 5 * 4

      val probe = TestProbe()
      val cycles = 9

      1 to cycles foreach { i ⇒
        redeliverTestChannel ! Deliver(Persistent(i), probe.ref.path)
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

      redeliverTestChannel ! Deliver(PersistentRepr("a"), probe.ref.path)

      probe.expectMsgPF() { case m @ ConfirmablePersistent("a", _, redeliveries) ⇒ redeliveries should be(0) }
      probe.expectMsgPF() { case m @ ConfirmablePersistent("a", _, redeliveries) ⇒ redeliveries should be(1) }
      probe.expectMsgPF() { case m @ ConfirmablePersistent("a", _, redeliveries) ⇒ redeliveries should be(2) }
      probe.expectNoMsg(300 milliseconds)
    }
    "preserve message order to the same destination" in {
      val probe = TestProbe()
      val destination = system.actorOf(Props(classOf[TestReceiver], probe.ref))

      1 to 10 foreach { i ⇒
        defaultTestChannel ! Deliver(PersistentRepr(s"test-${i}"), destination.path)
      }

      1 to 10 foreach { i ⇒
        probe.expectMsg(s"test-${i}")
      }
    }
    "notify redelivery failure listener" in {
      val probe = TestProbe()
      val listener = system.actorOf(Props(classOf[TestListener], probe.ref))
      val channel = createRedeliverTestChannel(Some(listener))

      1 to 3 foreach { i ⇒ channel ! Deliver(Persistent(i), system.deadLetters.path) }

      probe.expectMsgAllOf(1, 2, 3)
      system.stop(channel)
    }
  }
}

class LeveldbChannelSpec extends ChannelSpec(PersistenceSpec.config("leveldb", "LeveldbChannelSpec"))
class InmemChannelSpec extends ChannelSpec(PersistenceSpec.config("inmem", "InmemChannelSpec"))

