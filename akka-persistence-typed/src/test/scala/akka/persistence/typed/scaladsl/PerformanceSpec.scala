/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import java.util.UUID

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, SupervisorStrategy }
import akka.persistence.typed.scaladsl.EventSourcedBehavior.CommandHandler
import akka.actor.testkit.typed.TE
import akka.actor.testkit.typed.scaladsl.TestProbe
import com.typesafe.config.ConfigFactory
import scala.concurrent.duration._

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.persistence.typed.PersistenceId
import org.scalatest.WordSpecLike

object PerformanceSpec {

  val config =
    """
      akka.persistence.performance.cycles.load = 100
      # more accurate throughput measurements
      #akka.persistence.performance.cycles.load = 200000
    """

  sealed trait Command

  case object StopMeasure extends Command

  case class FailAt(sequence: Long) extends Command

  case class CommandWithEvent(evt: String) extends Command

  class Measure(numberOfMessages: Int) {
    private val NanoToSecond = 1000.0 * 1000 * 1000

    private var startTime: Long = 0L
    private var stopTime: Long = 0L

    def startMeasure(): Unit = {
      startTime = System.nanoTime
    }

    def stopMeasure(): Double = {
      stopTime = System.nanoTime
      NanoToSecond * numberOfMessages / (stopTime - startTime)
    }
  }

  case class Parameters(var persistCalls: Long = 0L, var failAt: Long = -1) {
    def every(num: Long): Boolean = persistCalls % num == 0

    def shouldFail: Boolean = persistCalls == failAt

    def failureWasDefined: Boolean = failAt != -1L
  }

  def behavior(name: String, probe: TestProbe[Command])(other: (Command, Parameters) ⇒ Effect[String, String]) = {
    Behaviors.supervise({
      val parameters = Parameters()
      EventSourcedBehavior[Command, String, String](
        persistenceId = PersistenceId(name),
        "",
        commandHandler = CommandHandler.command {
          case StopMeasure      ⇒ Effect.none.thenRun(_ ⇒ probe.ref ! StopMeasure)
          case FailAt(sequence) ⇒ Effect.none.thenRun(_ ⇒ parameters.failAt = sequence)
          case command          ⇒ other(command, parameters)
        },
        eventHandler = {
          case (state, _) ⇒ state
        }
      ).onRecoveryCompleted { _ ⇒
          if (parameters.every(1000)) print("r")
        }
    }).onFailure(SupervisorStrategy.restart)
  }

  def eventSourcedTestPersistenceBehavior(name: String, probe: TestProbe[Command]) =
    behavior(name, probe) {
      case (CommandWithEvent(evt), parameters) ⇒
        Effect.persist(evt).thenRun(_ ⇒ {
          parameters.persistCalls += 1
          if (parameters.every(1000)) print(".")
          if (parameters.shouldFail) throw TE("boom")
        })
      case _ ⇒ Effect.none
    }
}

class PerformanceSpec extends ScalaTestWithActorTestKit(ConfigFactory.parseString(s"""
      akka.actor.serialize-creators = off
      akka.actor.serialize-messages = off
      akka.actor.warn-about-java-serializer-usage = off
      akka.persistence.publish-plugin-commands = on
      akka.persistence.journal.plugin = "akka.persistence.journal.leveldb"
      akka.persistence.journal.leveldb.dir = "target/journal-PerformanceSpec"
      akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
      akka.persistence.snapshot-store.local.dir = "target/snapshots-PerformanceSpec/"
      akka.test.single-expect-default = 10s
      """).withFallback(ConfigFactory.parseString(PerformanceSpec.config))) with WordSpecLike {

  import PerformanceSpec._

  val loadCycles = system.settings.config.getInt("akka.persistence.performance.cycles.load")

  def stressPersistentActor(persistentActor: ActorRef[Command], probe: TestProbe[Command],
                            failAt: Option[Long], description: String): Unit = {
    failAt foreach { persistentActor ! FailAt(_) }
    val m = new Measure(loadCycles)
    m.startMeasure()
    1 to loadCycles foreach { i ⇒ persistentActor ! CommandWithEvent(s"msg$i") }
    persistentActor ! StopMeasure
    probe.expectMessage(100.seconds, StopMeasure)
    println(f"\nthroughput = ${m.stopMeasure()}%.2f $description per second")
  }

  def stressEventSourcedPersistentActor(failAt: Option[Long]): Unit = {
    val probe = TestProbe[Command]
    val name = s"${this.getClass.getSimpleName}-${UUID.randomUUID().toString}"
    val persistentActor = spawn(eventSourcedTestPersistenceBehavior(name, probe), name)
    stressPersistentActor(persistentActor, probe, failAt, "persistent events")
  }

  "An event sourced persistent actor" should {
    "have some reasonable throughput" in {
      stressEventSourcedPersistentActor(None)
    }
    "have some reasonable throughput under failure conditions" in {
      stressEventSourcedPersistentActor(Some(loadCycles / 10))
    }
  }
}
