/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Try

import akka.actor.testkit.typed.TestException
import akka.actor.testkit.typed.TestKitSettings
import akka.actor.testkit.typed.scaladsl._
import akka.actor.typed.ActorRef
import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.AtomicWrite
import akka.persistence.journal.inmem.InmemJournal
import akka.persistence.typed.EventRejectedException
import akka.persistence.typed.PersistenceId
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpecLike

class ChaosJournal extends InmemJournal {
  var counts = Map.empty[String, Int]
  var failRecovery = true
  var reject = true

  override def asyncWriteMessages(messages: immutable.Seq[AtomicWrite]): Future[immutable.Seq[Try[Unit]]] = {
    val pid = messages.head.persistenceId
    counts = counts.updated(pid, counts.getOrElse(pid, 0) + 1)
    if (pid == "fail-first-2" && counts(pid) <= 2) {
      Future.failed(TestException("database says no"))
    } else if (pid.startsWith("fail-fifth") && counts(pid) == 5) {
      Future.failed(TestException("database says no"))
    } else if (pid == "reject-first" && reject) {
      reject = false
      Future.successful(messages.map(_ ⇒ Try {
        throw TestException("I don't like it")
      }))
    } else {
      super.asyncWriteMessages(messages)
    }
  }

  override def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] = {
    if (persistenceId == "fail-recovery-once" && failRecovery) {
      failRecovery = false
      Future.failed(TestException("Nah"))
    } else if (persistenceId == "fail-recovery") {
      Future.failed(TestException("Nope"))
    } else {
      super.asyncReadHighestSequenceNr(persistenceId, fromSequenceNr)
    }
  }
}

object EventSourcedBehaviorFailureSpec {

  val conf: Config = ConfigFactory.parseString(
    s"""
      akka.loglevel = DEBUG
      akka.persistence.journal.plugin = "failure-journal"
      failure-journal = $${akka.persistence.journal.inmem}
      failure-journal {
        class = "akka.persistence.typed.scaladsl.ChaosJournal"
      }
    """).withFallback(ConfigFactory.defaultReference()).resolve()
}

class EventSourcedBehaviorFailureSpec extends ScalaTestWithActorTestKit(EventSourcedBehaviorFailureSpec.conf) with WordSpecLike {

  implicit val testSettings: TestKitSettings = TestKitSettings(system)

  def failingPersistentActor(pid: PersistenceId, probe: ActorRef[String]): EventSourcedBehavior[String, String, String] =
    EventSourcedBehavior[String, String, String](
      pid, "",
      (_, cmd) ⇒ {
        probe.tell("persisting")
        Effect.persist(cmd)
      },
      (state, event) ⇒ {
        probe.tell(event)
        state + event
      }
    ).onRecoveryCompleted { _ ⇒
        probe.tell("starting")
      }.onPersistFailure(SupervisorStrategy.restartWithBackoff(1.milli, 5.millis, 0.1)
        .withLoggingEnabled(enabled = false))

  "A typed persistent actor (failures)" must {

    "call onRecoveryFailure when replay fails" in {
      val notUsedProbe = TestProbe[String]()
      val probe = TestProbe[Throwable]()
      spawn(failingPersistentActor(PersistenceId("fail-recovery"), notUsedProbe.ref)
        .onRecoveryFailure(t ⇒ probe.ref ! t))

      probe.expectMessageType[TestException].message shouldEqual "Nope"
    }

    "handle exceptions in onRecoveryFailure" in {
      val probe = TestProbe[String]()
      val pa = spawn(failingPersistentActor(PersistenceId("fail-recovery-twice"), probe.ref)
        .onRecoveryFailure(_ ⇒ throw TestException("recovery call back failure")))
      pa ! "one"
      probe.expectMessage("starting")
      probe.expectMessage("persisting")
      probe.expectMessage("one")
    }

    "restart with backoff" in {
      val probe = TestProbe[String]()
      val behav = failingPersistentActor(PersistenceId("fail-first-2"), probe.ref)
      val c = spawn(behav)
      probe.expectMessage("starting")
      // fail
      c ! "one"
      probe.expectMessage("persisting")
      probe.expectMessage("one")
      probe.expectMessage("starting")
      // fail
      c ! "two"
      probe.expectMessage("persisting")
      probe.expectMessage("two")
      probe.expectMessage("starting")
      // work!
      c ! "three"
      probe.expectMessage("persisting")
      probe.expectMessage("three")
      // no restart
      probe.expectNoMessage()
    }

    "restart with backoff for recovery" in {
      val probe = TestProbe[String]()
      val behav = failingPersistentActor(PersistenceId("fail-recovery-once"), probe.ref)
      spawn(behav)
      // First time fails, second time should work and call onRecoveryComplete
      probe.expectMessage("starting")
      probe.expectNoMessage()
    }

    "handles rejections" in {
      val probe = TestProbe[String]()
      val behav =
        Behaviors.supervise(
          failingPersistentActor(PersistenceId("reject-first"), probe.ref)).onFailure[EventRejectedException](
            SupervisorStrategy.restartWithBackoff(1.milli, 5.millis, 0.1)
              .withLoggingEnabled(enabled = false))
      val c = spawn(behav)
      // First time fails, second time should work and call onRecoveryComplete
      probe.expectMessage("starting")
      c ! "one"
      probe.expectMessage("persisting")
      probe.expectMessage("one")
      probe.expectMessage("starting")
      c ! "two"
      probe.expectMessage("persisting")
      probe.expectMessage("two")
      // no restart
      probe.expectNoMessage()
    }
  }
}
