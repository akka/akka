/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.persistence

import akka.actor._
import akka.persistence.AtLeastOnceDelivery.{ AtLeastOnceDeliverySnapshot, UnconfirmedWarning }
import akka.testkit._
import com.typesafe.config._

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

object AtLeastOnceDeliverySpec {

  case class Req(payload: String)
  case object ReqAck
  case object InvalidReq

  sealed trait Evt
  case class AcceptedReq(payload: String, destination: ActorPath) extends Evt
  case class ReqDone(id: Long) extends Evt

  case class Action(id: Long, payload: String)
  case class ActionAck(id: Long)
  case object Boom
  case object SaveSnap
  case class Snap(deliverySnapshot: AtLeastOnceDeliverySnapshot) // typically includes some user data as well

  def senderProps(testActor: ActorRef, name: String,
                  redeliverInterval: FiniteDuration, warnAfterNumberOfUnconfirmedAttempts: Int,
                  async: Boolean, destinations: Map[String, ActorPath]): Props =
    Props(new Sender(testActor, name, redeliverInterval, warnAfterNumberOfUnconfirmedAttempts, async, destinations))

  class Sender(testActor: ActorRef,
               name: String,
               override val redeliverInterval: FiniteDuration,
               override val warnAfterNumberOfUnconfirmedAttempts: Int,
               async: Boolean,
               destinations: Map[String, ActorPath])
    extends PersistentActor with AtLeastOnceDelivery with ActorLogging {

    override def persistenceId: String = name

    // simplistic confirmation mechanism, to tell the requester that a snapshot succeeded
    var lastSnapshotAskedForBy: Option[ActorRef] = None

    def updateState(evt: Evt): Unit = evt match {
      case AcceptedReq(payload, destination) ⇒
        log.debug(s"deliver(destination, deliveryId ⇒ Action(deliveryId, $payload)), recovery: " + recoveryRunning)
        deliver(destination, deliveryId ⇒ Action(deliveryId, payload))

      case ReqDone(id) ⇒
        log.debug(s"confirmDelivery($id), recovery: " + recoveryRunning)
        confirmDelivery(id)
    }

    val receiveCommand: Receive = {
      case Req(payload) ⇒
        if (payload.isEmpty)
          sender() ! InvalidReq
        else {
          val destination = destinations(payload.take(1).toUpperCase)
          if (async)
            persistAsync(AcceptedReq(payload, destination)) { evt ⇒
              updateState(evt)
              sender() ! ReqAck
            }
          else
            persist(AcceptedReq(payload, destination)) { evt ⇒
              updateState(evt)
              sender() ! ReqAck
            }
        }

      case ActionAck(id) ⇒
        log.debug("Sender got ack {}", id)
        if (confirmDelivery(id))
          if (async)
            persistAsync(ReqDone(id)) { evt ⇒ updateState(evt) }
          else
            persist(ReqDone(id)) { evt ⇒ updateState(evt) }

      case Boom ⇒
        log.debug("Boom!")
        throw new RuntimeException("boom") with NoStackTrace

      case SaveSnap ⇒
        log.debug("Save snapshot!")
        lastSnapshotAskedForBy = Some(sender())
        saveSnapshot(Snap(getDeliverySnapshot))

      case success: SaveSnapshotSuccess ⇒
        log.debug("Snapshot success!")
        lastSnapshotAskedForBy.map(_ ! success)

      case w: UnconfirmedWarning ⇒
        log.debug("Sender got unconfirmed warning {}", w)
        testActor ! w

    }

    def receiveRecover: Receive = {
      case evt: Evt ⇒
        updateState(evt)

      case SnapshotOffer(_, Snap(deliverySnapshot)) ⇒
        setDeliverySnapshot(deliverySnapshot)
    }
  }

  def destinationProps(testActor: ActorRef): Props =
    Props(new Destination(testActor))

  class Destination(testActor: ActorRef) extends Actor with ActorLogging {

    var allReceived = Set.empty[Long]

    def receive = {
      case a @ Action(id, payload) ⇒
        // discard duplicates (naive impl)
        if (!allReceived.contains(id)) {
          log.debug("Destination got {}, all count {}", a, allReceived.size + 1)
          testActor ! a
          allReceived += id
        }
        sender() ! ActionAck(id)
    }
  }

  def unreliableProps(dropMod: Int, target: ActorRef): Props =
    Props(new Unreliable(dropMod, target))

  class Unreliable(dropMod: Int, target: ActorRef) extends Actor with ActorLogging {
    var count = 0
    def receive = {
      case msg ⇒
        count += 1
        if (count % dropMod != 0) {
          log.debug("Pass msg {} count {}", msg, count)
          target forward msg
        } else {
          log.debug("Drop msg {} count {}", msg, count)
        }
    }
  }

}

abstract class AtLeastOnceDeliverySpec(config: Config) extends AkkaSpec(config) with PersistenceSpec with ImplicitSender {
  import akka.persistence.AtLeastOnceDeliverySpec._

  "AtLeastOnceDelivery" must {
    "deliver messages in order when nothing is lost" in {
      val probeA = TestProbe()
      val destinations = Map("A" -> system.actorOf(destinationProps(probeA.ref)).path)
      val snd = system.actorOf(senderProps(testActor, name, 500.millis, 5, async = false, destinations), name)
      snd ! Req("a")
      expectMsg(ReqAck)
      probeA.expectMsg(Action(1, "a"))
      probeA.expectNoMsg(1.second)
    }

    "re-deliver lost messages" in {
      val probeA = TestProbe()
      val dst = system.actorOf(destinationProps(probeA.ref))
      val destinations = Map("A" -> system.actorOf(unreliableProps(3, dst)).path)
      val snd = system.actorOf(senderProps(testActor, name, 500.millis, 5, async = false, destinations), name)
      snd ! Req("a-1")
      expectMsg(ReqAck)
      probeA.expectMsg(Action(1, "a-1"))

      snd ! Req("a-2")
      expectMsg(ReqAck)
      probeA.expectMsg(Action(2, "a-2"))

      snd ! Req("a-3")
      snd ! Req("a-4")
      expectMsg(ReqAck)
      expectMsg(ReqAck)
      // a-3 was lost
      probeA.expectMsg(Action(4, "a-4"))
      // and then re-delivered
      probeA.expectMsg(Action(3, "a-3"))
      probeA.expectNoMsg(1.second)
    }

    "re-deliver lost messages after restart" in {
      val probeA = TestProbe()
      val dst = system.actorOf(destinationProps(probeA.ref))
      val destinations = Map("A" -> system.actorOf(unreliableProps(3, dst)).path)
      val snd = system.actorOf(senderProps(testActor, name, 500.millis, 5, async = false, destinations), name)
      snd ! Req("a-1")
      expectMsg(ReqAck)
      probeA.expectMsg(Action(1, "a-1"))

      snd ! Req("a-2")
      expectMsg(ReqAck)
      probeA.expectMsg(Action(2, "a-2"))

      snd ! Req("a-3")
      snd ! Req("a-4")
      expectMsg(ReqAck)
      expectMsg(ReqAck)
      // a-3 was lost
      probeA.expectMsg(Action(4, "a-4"))

      // trigger restart
      snd ! Boom

      // and then re-delivered
      probeA.expectMsg(Action(3, "a-3"))

      snd ! Req("a-5")
      expectMsg(ReqAck)
      probeA.expectMsg(Action(5, "a-5"))

      probeA.expectNoMsg(1.second)
    }

    "re-send replayed deliveries with an 'initially in-order' strategy, before delivering fresh messages" in {
      val probeA = TestProbe()
      val dst = system.actorOf(destinationProps(probeA.ref))
      val destinations = Map("A" -> system.actorOf(unreliableProps(2, dst)).path)
      val snd = system.actorOf(senderProps(testActor, name, 500.millis, 5, async = false, destinations), name)
      snd ! Req("a-1")
      expectMsg(ReqAck)
      probeA.expectMsg(Action(1, "a-1"))

      snd ! Req("a-2")
      expectMsg(ReqAck)
      // a-2 was lost

      snd ! Req("a-3")
      expectMsg(ReqAck)
      probeA.expectMsg(Action(3, "a-3"))

      snd ! Req("a-4")
      expectMsg(ReqAck)
      // a-4 was lost

      // trigger restart
      snd ! Boom
      snd ! Req("a-5")
      expectMsg(ReqAck)

      // and then re-delivered
      probeA.expectMsg(Action(2, "a-2")) // re-delivered
      // a-4 was re-delivered but lost
      probeA.expectMsg(Action(5, "a-5")) // re-delivered
      probeA.expectMsg(Action(4, "a-4")) // re-delivered, 3rd time

      probeA.expectNoMsg(1.second)
    }

    "restore state from snapshot" in {
      val probeA = TestProbe()
      val dst = system.actorOf(destinationProps(probeA.ref))
      val destinations = Map("A" -> system.actorOf(unreliableProps(3, dst)).path)
      val snd = system.actorOf(senderProps(testActor, name, 1000.millis, 5, async = false, destinations), name)
      snd ! Req("a-1")
      expectMsg(ReqAck)
      probeA.expectMsg(Action(1, "a-1"))

      snd ! Req("a-2")
      expectMsg(ReqAck)
      probeA.expectMsg(Action(2, "a-2"))

      snd ! Req("a-3")
      snd ! Req("a-4")
      snd ! SaveSnap
      expectMsg(ReqAck)
      expectMsg(ReqAck)
      // a-3 was lost
      probeA.expectMsg(Action(4, "a-4"))

      // after snapshot succeeded
      expectMsgType[SaveSnapshotSuccess]

      // trigger restart
      snd ! Boom

      // and then re-delivered
      probeA.expectMsg(Action(3, "a-3"))

      snd ! Req("a-5")
      expectMsg(ReqAck)
      probeA.expectMsg(Action(5, "a-5"))

      probeA.expectNoMsg(1.second)
    }

    "warn about unconfirmed messages" in {
      val probeA = TestProbe()
      val probeB = TestProbe()
      val destinations = Map("A" -> probeA.ref.path, "B" -> probeB.ref.path)
      val snd = system.actorOf(senderProps(testActor, name, 500.millis, 3, async = false, destinations), name)
      snd ! Req("a-1")
      snd ! Req("b-1")
      snd ! Req("b-2")
      expectMsg(ReqAck)
      expectMsg(ReqAck)
      expectMsg(ReqAck)
      val unconfirmed = receiveWhile(3.seconds) {
        case UnconfirmedWarning(unconfirmed) ⇒ unconfirmed
      }.flatten
      unconfirmed.map(_.destination).toSet should be(Set(probeA.ref.path, probeB.ref.path))
      unconfirmed.map(_.message).toSet should be(Set(Action(1, "a-1"), Action(2, "b-1"), Action(3, "b-2")))
      system.stop(snd)
    }

    "re-deliver many lost messages" in {
      val probeA = TestProbe()
      val probeB = TestProbe()
      val probeC = TestProbe()
      val dstA = system.actorOf(destinationProps(probeA.ref), "destination-a")
      val dstB = system.actorOf(destinationProps(probeB.ref), "destination-b")
      val dstC = system.actorOf(destinationProps(probeC.ref), "destination-c")
      val destinations = Map(
        "A" -> system.actorOf(unreliableProps(2, dstA), "unreliable-a").path,
        "B" -> system.actorOf(unreliableProps(5, dstB), "unreliable-b").path,
        "C" -> system.actorOf(unreliableProps(3, dstC), "unreliable-c").path)
      val snd = system.actorOf(senderProps(testActor, name, 1000.millis, 5, async = true, destinations), name)
      val N = 100
      for (n ← 1 to N) {
        snd ! Req("a-" + n)
      }
      for (n ← 1 to N) {
        snd ! Req("b-" + n)
      }
      for (n ← 1 to N) {
        snd ! Req("c-" + n)
      }
      val deliverWithin = 20.seconds
      probeA.receiveN(N, deliverWithin).map { case a: Action ⇒ a.payload }.toSet should be((1 to N).map(n ⇒ "a-" + n).toSet)
      probeB.receiveN(N, deliverWithin).map { case a: Action ⇒ a.payload }.toSet should be((1 to N).map(n ⇒ "b-" + n).toSet)
      probeC.receiveN(N, deliverWithin).map { case a: Action ⇒ a.payload }.toSet should be((1 to N).map(n ⇒ "c-" + n).toSet)
    }

  }
}

class LeveldbAtLeastOnceDeliverySpec extends AtLeastOnceDeliverySpec(
  // TODO disable debug logging once happy with stability of this test
  ConfigFactory.parseString("""akka.logLevel = DEBUG""") withFallback PersistenceSpec.config("leveldb", "AtLeastOnceDeliverySpec"))

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class InmemAtLeastOnceDeliverySpec extends AtLeastOnceDeliverySpec(PersistenceSpec.config("inmem", "AtLeastOnceDeliverySpec"))
