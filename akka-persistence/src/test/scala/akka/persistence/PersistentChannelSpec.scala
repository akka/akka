/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import scala.concurrent.duration._
import scala.language.postfixOps

import com.typesafe.config._

import akka.actor._
import akka.testkit._

object PersistentChannelSpec {
  class SlowDestination(probe: ActorRef, maxReceived: Long) extends Actor {
    import context.dispatcher

    val delay = 100.millis
    var received = Vector.empty[ConfirmablePersistent]

    def receive = {
      case cp: ConfirmablePersistent ⇒
        if (received.isEmpty) context.system.scheduler.scheduleOnce(delay, self, "confirm")
        received :+= cp
      case "confirm" ⇒
        if (received.size > maxReceived) probe ! s"number of received messages to high: ${received.size}"
        else probe ! received.head.payload
        received.head.confirm()
        received = received.tail
        if (received.nonEmpty) context.system.scheduler.scheduleOnce(delay, self, "confirm")
    }
  }
}

abstract class PersistentChannelSpec(config: Config) extends ChannelSpec(config) {
  import PersistentChannelSpec._

  private def redeliverChannelSettings(listener: Option[ActorRef]): PersistentChannelSettings =
    PersistentChannelSettings(redeliverMax = 2, redeliverInterval = 100 milliseconds, redeliverFailureListener = listener, idleTimeout = 5.seconds)

  private def createDefaultTestChannel(name: String): ActorRef =
    system.actorOf(PersistentChannel.props(s"${name}-default", PersistentChannelSettings(idleTimeout = 5.seconds)))

  override def createDefaultTestChannel(): ActorRef =
    createDefaultTestChannel(name)

  override def createRedeliverTestChannel(): ActorRef =
    system.actorOf(PersistentChannel.props(s"${name}-redeliver", redeliverChannelSettings(None)))

  override def createRedeliverTestChannel(listener: Option[ActorRef]): ActorRef =
    system.actorOf(PersistentChannel.props(s"${name}-redeliver-listener", redeliverChannelSettings(listener)))

  "A persistent channel" must {
    "support Persistent replies to Deliver senders" in {
      val destProbe = TestProbe()
      val replyProbe = TestProbe()

      val channel1 = system.actorOf(PersistentChannel.props(s"${name}-with-reply", PersistentChannelSettings(replyPersistent = true)))

      channel1 tell (Deliver(Persistent("a"), destProbe.ref.path), replyProbe.ref)
      destProbe.expectMsgPF() { case cp @ ConfirmablePersistent("a", _, _) ⇒ cp.confirm() }
      replyProbe.expectMsgPF() { case Persistent("a", _) ⇒ }

      channel1 tell (Deliver(PersistentRepr("b", sequenceNr = 13), destProbe.ref.path), replyProbe.ref)
      destProbe.expectMsgPF() { case cp @ ConfirmablePersistent("b", 13, _) ⇒ cp.confirm() }
      replyProbe.expectMsgPF() { case Persistent("b", 13) ⇒ }

      system.stop(channel1)
    }
    "not modify certain persistent message fields" in {
      val destProbe = TestProbe()

      val persistent1 = PersistentRepr(payload = "a", persistenceId = "p1", confirms = List("c1", "c2"), sender = defaultTestChannel, sequenceNr = 13)
      val persistent2 = PersistentRepr(payload = "b", persistenceId = "p1", confirms = List("c1", "c2"), sender = defaultTestChannel)

      defaultTestChannel ! Deliver(persistent1, destProbe.ref.path)
      defaultTestChannel ! Deliver(persistent2, destProbe.ref.path)

      destProbe.expectMsgPF() { case cp @ ConfirmablePersistentImpl("a", 13, "p1", _, _, Seq("c1", "c2"), _, _, channel) ⇒ cp.confirm() }
      destProbe.expectMsgPF() { case cp @ ConfirmablePersistentImpl("b", 2, "p1", _, _, Seq("c1", "c2"), _, _, channel) ⇒ cp.confirm() }
    }
    "redeliver un-confirmed stored messages during recovery" in {
      val confirmProbe = TestProbe()
      val forwardProbe = TestProbe()

      subscribeToConfirmation(confirmProbe)

      val channel1 = createDefaultTestChannel("extra")
      channel1 tell (Deliver(Persistent("a1"), forwardProbe.ref.path), null)
      channel1 tell (Deliver(Persistent("a2"), forwardProbe.ref.path), null)

      forwardProbe.expectMsgPF() { case m @ ConfirmablePersistent("a1", _, _) ⇒ /* no confirmation */ }
      forwardProbe.expectMsgPF() { case m @ ConfirmablePersistent("a2", _, _) ⇒ m.confirm() }

      awaitConfirmation(confirmProbe)

      system.stop(channel1)

      val channel2 = createDefaultTestChannel("extra")
      channel2 tell (Deliver(Persistent("a3"), forwardProbe.ref.path), null)

      forwardProbe.expectMsgPF() { case m @ ConfirmablePersistent("a1", _, _) ⇒ m.confirm() }
      forwardProbe.expectMsgPF() { case m @ ConfirmablePersistent("a3", _, _) ⇒ m.confirm() }

      awaitConfirmation(confirmProbe)
      awaitConfirmation(confirmProbe)

      system.stop(channel2)
    }
    "not flood destinations" in {
      val probe = TestProbe()
      val settings = PersistentChannelSettings(
        redeliverMax = 0,
        redeliverInterval = 1.minute,
        pendingConfirmationsMax = 4,
        pendingConfirmationsMin = 2)

      val channel = system.actorOf(PersistentChannel.props(s"${name}-watermark", settings))
      val destination = system.actorOf(Props(classOf[SlowDestination], probe.ref, settings.pendingConfirmationsMax))

      1 to 10 foreach { i ⇒ channel ! Deliver(Persistent(i), destination.path) }
      1 to 10 foreach { i ⇒ probe.expectMsg(i) }

      system.stop(channel)
    }
    "redeliver on reset" in {
      val probe = TestProbe()
      val settings = PersistentChannelSettings(
        redeliverMax = 0,
        redeliverInterval = 1.minute,
        pendingConfirmationsMax = 4,
        pendingConfirmationsMin = 2)

      val channel = system.actorOf(PersistentChannel.props(s"${name}-reset", settings))

      1 to 3 foreach { i ⇒ channel ! Deliver(Persistent(i), probe.ref.path) }
      1 to 3 foreach { i ⇒ probe.expectMsgPF() { case ConfirmablePersistent(`i`, _, _) ⇒ } }

      channel ! Reset

      1 to 3 foreach { i ⇒ probe.expectMsgPF() { case ConfirmablePersistent(`i`, _, _) ⇒ } }

      system.stop(channel)
    }
  }
}

class LeveldbPersistentChannelSpec extends PersistentChannelSpec(PersistenceSpec.config("leveldb", "LeveldbPersistentChannelSpec"))
class InmemPersistentChannelSpec extends PersistentChannelSpec(PersistenceSpec.config("inmem", "InmemPersistentChannelSpec"))

