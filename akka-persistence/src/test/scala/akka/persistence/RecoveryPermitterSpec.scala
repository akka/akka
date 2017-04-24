/*
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.persistence

import scala.concurrent.duration._
import akka.actor._
import akka.event.Logging
import akka.event.Logging.Warning
import akka.testkit.{ EventFilter, ImplicitSender, TestEvent }
import com.typesafe.config.ConfigFactory
import akka.testkit.TestProbe
import akka.testkit.TestActors

object RecoveryPermitterSpec {

  def testProps(name: String, probe: ActorRef): Props =
    Props(new TestPersistentActor(name, probe))

  class TestPersistentActor(name: String, probe: ActorRef) extends PersistentActor {

    override def persistenceId = name

    override def postStop(): Unit = {
      probe ! "postStop"
    }

    override def receiveRecover: Receive = {
      case RecoveryCompleted ⇒
        probe ! RecoveryCompleted
    }
    override def receiveCommand: Receive = {
      case "stop" ⇒
        context.stop(self)
    }
  }

}

class RecoveryPermitterSpec extends PersistenceSpec(ConfigFactory.parseString(
  s"""
    akka.persistence.max-concurrent-recoveries = 3
    akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
    akka.actor.warn-about-java-serializer-usage = off
  """)) with ImplicitSender {
  import RecoveryPermitterSpec._
  import RecoveryPermitter._

  val permitter = Persistence(system).recoveryPermitter
  val p1 = TestProbe()
  val p2 = TestProbe()
  val p3 = TestProbe()
  val p4 = TestProbe()
  val p5 = TestProbe()

  def requestPermit(p: TestProbe): Unit = {
    permitter.tell(RequestRecoveryPermit, p.ref)
    p.expectMsg(RecoveryPermitGranted)
  }

  "RecoveryPermitter" must {
    "grant permits up to the limit" in {
      requestPermit(p1)
      requestPermit(p2)
      requestPermit(p3)

      permitter.tell(RequestRecoveryPermit, p4.ref)
      permitter.tell(RequestRecoveryPermit, p5.ref)
      p4.expectNoMsg(100.millis)
      p5.expectNoMsg(10.millis)

      permitter.tell(ReturnRecoveryPermit, p2.ref)
      p4.expectMsg(RecoveryPermitGranted)
      p5.expectNoMsg(100.millis)

      permitter.tell(ReturnRecoveryPermit, p1.ref)
      p5.expectMsg(RecoveryPermitGranted)

      permitter.tell(ReturnRecoveryPermit, p3.ref)
      permitter.tell(ReturnRecoveryPermit, p4.ref)
      permitter.tell(ReturnRecoveryPermit, p5.ref)
    }

    "grant recovery when all permits not used" in {
      requestPermit(p1)

      system.actorOf(testProps("p2", p2.ref))
      p2.expectMsg(RecoveryCompleted)
      permitter.tell(ReturnRecoveryPermit, p1.ref)
    }

    "delay recovery when all permits used" in {
      requestPermit(p1)
      requestPermit(p2)
      requestPermit(p3)

      val persistentActor = system.actorOf(testProps("p4", p4.ref))
      p4.watch(persistentActor)
      persistentActor ! "stop"
      p4.expectNoMsg(200.millis)

      permitter.tell(ReturnRecoveryPermit, p3.ref)
      p4.expectMsg(RecoveryCompleted)
      p4.expectMsg("postStop")
      p4.expectTerminated(persistentActor)

      permitter.tell(ReturnRecoveryPermit, p1.ref)
      permitter.tell(ReturnRecoveryPermit, p2.ref)
    }

    "return permit when actor is pre-maturely terminated before holding permit" in {
      requestPermit(p1)
      requestPermit(p2)
      requestPermit(p3)

      val persistentActor = system.actorOf(testProps("p4", p4.ref))
      p4.expectNoMsg(100.millis)

      permitter.tell(RequestRecoveryPermit, p5.ref)
      p5.expectNoMsg(100.millis)

      // PoisonPill is not stashed
      persistentActor ! PoisonPill
      p4.expectMsg("postStop")

      // persistentActor didn't hold a permit so still
      p5.expectNoMsg(100.millis)

      permitter.tell(ReturnRecoveryPermit, p1.ref)
      p5.expectMsg(RecoveryPermitGranted)

      permitter.tell(ReturnRecoveryPermit, p2.ref)
      permitter.tell(ReturnRecoveryPermit, p3.ref)
      permitter.tell(ReturnRecoveryPermit, p5.ref)
    }

    "return permit when actor is pre-maturely terminated when holding permit" in {
      val actor = system.actorOf(TestActors.forwardActorProps(p1.ref))
      permitter.tell(RequestRecoveryPermit, actor)
      p1.expectMsg(RecoveryPermitGranted)

      requestPermit(p2)
      requestPermit(p3)

      permitter.tell(RequestRecoveryPermit, p4.ref)
      p4.expectNoMsg(100.millis)

      actor ! PoisonPill
      p4.expectMsg(RecoveryPermitGranted)

      permitter.tell(ReturnRecoveryPermit, p2.ref)
      permitter.tell(ReturnRecoveryPermit, p3.ref)
      permitter.tell(ReturnRecoveryPermit, p4.ref)
    }
  }

}

