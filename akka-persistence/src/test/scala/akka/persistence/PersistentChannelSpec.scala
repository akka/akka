/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import scala.concurrent.duration._
import scala.language.postfixOps

import com.typesafe.config._

import akka.actor._
import akka.testkit._

abstract class PersistentChannelSpec(config: Config) extends ChannelSpec(config) {
  override def redeliverChannelSettings: PersistentChannelSettings =
    PersistentChannelSettings(redeliverMax = 2, redeliverInterval = 100 milliseconds)

  override def createDefaultTestChannel(): ActorRef =
    system.actorOf(PersistentChannel.props(s"${name}-default", PersistentChannelSettings()))

  override def createRedeliverTestChannel(): ActorRef =
    system.actorOf(PersistentChannel.props(s"${name}-redeliver", redeliverChannelSettings))

  "A persistent channel" must {
    "support disabling and re-enabling delivery" in {
      val confirmProbe = TestProbe()

      subscribeToConfirmation(confirmProbe)

      defaultTestChannel ! Deliver(Persistent("a"), testActor)

      expectMsgPF() { case m @ ConfirmablePersistent("a", _, _) ⇒ m.confirm() }
      awaitConfirmation(confirmProbe)

      defaultTestChannel ! DisableDelivery
      defaultTestChannel ! Deliver(Persistent("b"), testActor)
      defaultTestChannel ! EnableDelivery
      defaultTestChannel ! Deliver(Persistent("c"), testActor)

      expectMsgPF() { case m @ ConfirmablePersistent("b", _, _) ⇒ m.confirm() }
      expectMsgPF() { case m @ ConfirmablePersistent("c", _, _) ⇒ m.confirm() }
    }
    "support Persistent replies to Deliver senders" in {
      val channel1 = system.actorOf(PersistentChannel.props(s"${name}-with-reply", PersistentChannelSettings(replyPersistent = true)))

      channel1 ! Deliver(Persistent("a"), system.deadLetters)
      expectMsgPF() { case Persistent("a", 1) ⇒ }

      channel1 ! Deliver(PersistentRepr("b", sequenceNr = 13), system.deadLetters)
      expectMsgPF() { case Persistent("b", 13) ⇒ }

      system.stop(channel1)
    }
    "not modify certain persistent message fields" in {
      val persistent1 = PersistentRepr(payload = "a", processorId = "p1", confirms = List("c1", "c2"), sender = defaultTestChannel, sequenceNr = 13)
      val persistent2 = PersistentRepr(payload = "b", processorId = "p1", confirms = List("c1", "c2"), sender = defaultTestChannel)

      defaultTestChannel ! Deliver(persistent1, testActor)
      defaultTestChannel ! Deliver(persistent2, testActor)

      expectMsgPF() { case cp @ ConfirmablePersistentImpl("a", 13, "p1", _, _, _, Seq("c1", "c2"), _, _, channel) ⇒ cp.confirm() }
      expectMsgPF() { case cp @ ConfirmablePersistentImpl("b", 2, "p1", _, _, _, Seq("c1", "c2"), _, _, channel) ⇒ cp.confirm() }
    }
  }

  "A persistent channel" when {
    "used standalone" must {
      "redeliver un-confirmed stored messages during recovery" in {
        val confirmProbe = TestProbe()
        val forwardProbe = TestProbe()

        subscribeToConfirmation(confirmProbe)

        val channel1 = createDefaultTestChannel()
        channel1 tell (Deliver(Persistent("a1"), forwardProbe.ref), null)
        channel1 tell (Deliver(Persistent("a2"), forwardProbe.ref), null)

        forwardProbe.expectMsgPF() { case m @ ConfirmablePersistent("a1", _, _) ⇒ /* no confirmation */ }
        forwardProbe.expectMsgPF() { case m @ ConfirmablePersistent("a2", _, _) ⇒ m.confirm() }

        awaitConfirmation(confirmProbe)

        system.stop(channel1)

        val channel2 = createDefaultTestChannel()
        channel2 tell (Deliver(Persistent("a3"), forwardProbe.ref), null)

        forwardProbe.expectMsgPF() { case m @ ConfirmablePersistent("a1", _, _) ⇒ m.confirm() } // sender still valid, no need to resolve
        forwardProbe.expectMsgPF() { case m @ ConfirmablePersistent("a3", _, _) ⇒ m.confirm() }

        awaitConfirmation(confirmProbe)
        awaitConfirmation(confirmProbe)

        system.stop(channel2)
      }
    }
  }
}

class LeveldbPersistentChannelSpec extends PersistentChannelSpec(PersistenceSpec.config("leveldb", "LeveldbPersistentChannelSpec"))
class InmemPersistentChannelSpec extends PersistentChannelSpec(PersistenceSpec.config("inmem", "InmemPersistentChannelSpec"))

