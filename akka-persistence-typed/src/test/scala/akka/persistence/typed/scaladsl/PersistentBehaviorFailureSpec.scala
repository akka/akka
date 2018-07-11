/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import akka.actor.testkit.typed.TestKitSettings
import akka.actor.testkit.typed.scaladsl._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior, SupervisorStrategy, TypedAkkaSpecWithShutdown }
import akka.actor.testkit.typed.TE
import akka.persistence.AtomicWrite
import akka.persistence.journal.inmem.InmemJournal
import akka.persistence.typed.EventRejectedException
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.concurrent.Eventually

import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Try

class ChaosJournal extends InmemJournal {
  var count = 0
  var failRecovery = true
  var reject = true
  override def asyncWriteMessages(messages: immutable.Seq[AtomicWrite]): Future[immutable.Seq[Try[Unit]]] = {
    val pid = messages.head.persistenceId
    if (pid == "fail-first-2" && count < 2) {
      count += 1
      Future.failed(TE("database says no"))
    } else if (pid == "reject-first" && reject) {
      reject = false
      Future.successful(messages.map(aw ⇒ Try {
        throw TE("I don't like it")
      }))
    } else {
      super.asyncWriteMessages(messages)
    }
  }

  override def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] = {
    if (persistenceId == "fail-recovery-once" && failRecovery) {
      failRecovery = false
      Future.failed(TE("Nah"))
    } else {
      super.asyncReadHighestSequenceNr(persistenceId, fromSequenceNr)
    }
  }
}

object PersistentBehaviorFailureSpec {

  val conf = ConfigFactory.parseString(
    s"""
      akka.loglevel = DEBUG
      akka.persistence.journal.plugin = "failure-journal"
      failure-journal = $${akka.persistence.journal.inmem}
      failure-journal {
        class = "akka.persistence.typed.scaladsl.ChaosJournal"
      }
    """).withFallback(ConfigFactory.load("reference.conf")).resolve()
}

class PersistentBehaviorFailureSpec extends ActorTestKit with TypedAkkaSpecWithShutdown with Eventually {

  import PersistentBehaviorSpec._

  override lazy val config: Config = PersistentBehaviorFailureSpec.conf

  implicit val testSettings = TestKitSettings(system)

  def failingPersistentActor(pid: String, probe: ActorRef[String]): Behavior[String] = PersistentBehaviors.receive[String, String, String](
    pid, "",
    (ctx, state, cmd) ⇒ {
      probe.tell("persisting")
      Effect.persist(cmd)
    },
    (state, event) ⇒ {
      probe.tell(event)
      state + event
    }
  ).onRecoveryCompleted { (ctx, state) ⇒
      probe.tell("starting")
    }.onPersistFailure(SupervisorStrategy.restartWithBackoff(1.milli, 5.millis, 0.1))

  "A typed persistent actor (failures)" must {
    "restart with backoff" in {
      val probe = TestProbe[String]()
      val behav = failingPersistentActor("fail-first-2", probe.ref)
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
      val behav = failingPersistentActor("fail-recovery-once", probe.ref)
      spawn(behav)
      // First time fails, second time should work and call onRecoveryComplete
      probe.expectMessage("starting")
      probe.expectNoMessage()
    }

    "handles rejections" in {
      val probe = TestProbe[String]()
      val behav =
        Behaviors.supervise(
          failingPersistentActor("reject-first", probe.ref)).onFailure[EventRejectedException](
            SupervisorStrategy.restartWithBackoff(1.milli, 5.millis, 0.1))
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
