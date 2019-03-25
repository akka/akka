/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence

import akka.actor._
import akka.actor.SupervisorStrategy.{ Escalate, Stop }
import akka.testkit.{ AkkaSpec, ImplicitSender, TestProbe }
import scala.concurrent.duration._

import scala.util.control.NoStackTrace

object AtLeastOnceDeliveryCrashSpec {

  class StoppingStrategySupervisor(testProbe: ActorRef) extends Actor {
    import scala.concurrent.duration._

    override val supervisorStrategy = OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 10.seconds) {
      case _: IllegalStateException => Stop
      case t                        => super.supervisorStrategy.decider.applyOrElse(t, (_: Any) => Escalate)
    }

    val crashingActor = context.actorOf(Props(new CrashingActor(testProbe)), "CrashingActor")

    def receive: Receive = { case msg => crashingActor.forward(msg) }
  }

  object CrashingActor {
    case object Message
    case object CrashMessage
    case class SendingMessage(deliveryId: Long, recovering: Boolean)
  }

  class CrashingActor(testProbe: ActorRef) extends PersistentActor with AtLeastOnceDelivery with ActorLogging {
    import CrashingActor._

    override def persistenceId = self.path.name

    override def receiveRecover: Receive = {
      case Message => send()
      case CrashMessage =>
        log.debug("Crash it")
        throw new IllegalStateException("Intentionally crashed") with NoStackTrace
      case msg => log.debug("Recover message: " + msg)
    }

    override def receiveCommand: Receive = {
      case Message => persist(Message)(_ => send())
      case CrashMessage =>
        persist(CrashMessage) { evt =>
        }
    }

    def send() = {
      deliver(testProbe.path) { id =>
        SendingMessage(id, false)
      }
    }
  }

}

class AtLeastOnceDeliveryCrashSpec
    extends AkkaSpec(PersistenceSpec.config("inmem", "AtLeastOnceDeliveryCrashSpec", serialization = "off"))
    with ImplicitSender {
  import AtLeastOnceDeliveryCrashSpec._
  "At least once delivery" should {
    "not send when actor crashes" in {
      val testProbe = TestProbe()
      def createCrashActorUnderSupervisor() =
        system.actorOf(Props(new StoppingStrategySupervisor(testProbe.ref)), "supervisor")
      val superVisor = createCrashActorUnderSupervisor()
      superVisor ! CrashingActor.Message
      testProbe.expectMsgType[CrashingActor.SendingMessage]

      superVisor ! CrashingActor.CrashMessage
      val deathProbe = TestProbe()
      deathProbe.watch(superVisor)
      system.stop(superVisor)
      deathProbe.expectTerminated(superVisor)

      testProbe.expectNoMsg(250.millis)
      createCrashActorUnderSupervisor()
      testProbe.expectNoMsg(1.second)
    }
  }
}
