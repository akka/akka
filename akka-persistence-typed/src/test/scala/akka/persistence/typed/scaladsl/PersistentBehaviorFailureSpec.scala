/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import java.util.concurrent.atomic.AtomicInteger

import akka.Done
import akka.actor.testkit.typed.TestKitSettings
import akka.actor.testkit.typed.scaladsl._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior, SupervisorStrategy, Terminated, TypedAkkaSpecWithShutdown }
import akka.persistence.AtomicWrite
import akka.persistence.journal.inmem.InmemJournal
import akka.persistence.typed.PersistFailedException
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.concurrent.Eventually

import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Success, Try }

class ChaosJournal extends InmemJournal {
  var count = 0
  override def asyncWriteMessages(messages: immutable.Seq[AtomicWrite]): Future[immutable.Seq[Try[Unit]]] = {
    if (count >= 2) {
      super.asyncWriteMessages(messages)
    } else {
      count += 1
      Future.failed(new RuntimeException("database says no"))
    }
  }
}

object PersistentBehaviorFailureSpec {

  val config = ConfigFactory.parseString(
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

  override lazy val config: Config = PersistentBehaviorFailureSpec.config

  implicit val testSettings = TestKitSettings(system)

  val pidCounter = new AtomicInteger(0)
  private def nextPid(): String = s"c${pidCounter.incrementAndGet()}"

  "A typed persistent actor (failures)" must {
    "restart with backoff" in {
      val supervisedBehavior = Behaviors.supervise(counter(nextPid))
        .onFailure[PersistFailedException](SupervisorStrategy.restartWithBackoff(1.milli, 5.millis, 0.1))

      val c: ActorRef[Command] = spawn(supervisedBehavior)
      // fail
      c ! Increment
      Thread.sleep(10)
      // fail
      c ! Increment
      Thread.sleep(10)
      // work!
      c ! Increment
      val probe = TestProbe[State]
      c ! GetValue(probe.ref)
      probe.expectMessage(State(1, Vector(0)))
    }
  }

}
