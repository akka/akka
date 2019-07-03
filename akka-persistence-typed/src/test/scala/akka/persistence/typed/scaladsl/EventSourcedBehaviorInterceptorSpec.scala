/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.testkit.typed.scaladsl._
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.BehaviorInterceptor
import akka.actor.typed.TypedActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.persistence.typed.PersistenceId
import akka.testkit.EventFilter
import akka.testkit.TestEvent.Mute
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpecLike

object EventSourcedBehaviorInterceptorSpec {

  val journalId = "event-sourced-behavior-interceptor-spec"

  def config: Config = ConfigFactory.parseString(s"""
        akka.loglevel = INFO
        akka.loggers = [akka.testkit.TestEventListener]
        akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
        akka.persistence.journal.inmem.test-serialization = on
        """)

  def testBehavior(persistenceId: PersistenceId, probe: ActorRef[String]): Behavior[String] =
    Behaviors.setup { _ =>
      EventSourcedBehavior[String, String, String](
        persistenceId,
        emptyState = "",
        commandHandler = (_, command) =>
          command match {
            case _ =>
              Effect.persist(command).thenRun(newState => probe ! newState)
          },
        eventHandler = (state, evt) => state + evt)
    }

}

class EventSourcedBehaviorInterceptorSpec
    extends ScalaTestWithActorTestKit(EventSourcedBehaviorTimersSpec.config)
    with WordSpecLike {

  import EventSourcedBehaviorInterceptorSpec._

  val pidCounter = new AtomicInteger(0)
  private def nextPid(): PersistenceId = PersistenceId(s"c${pidCounter.incrementAndGet()})")

  import akka.actor.typed.scaladsl.adapter._
  // needed for the untyped event filter
  private implicit val untypedSystem: akka.actor.ActorSystem = system.toUntyped

  untypedSystem.eventStream.publish(Mute(EventFilter.warning(start = "No default snapshot store", occurrences = 1)))

  "EventSourcedBehavior interceptor" must {

    "be possible to combine with another interceptor" in {
      val probe = createTestProbe[String]()
      val pid = nextPid()

      val toUpper = new BehaviorInterceptor[String, String] {
        override def aroundReceive(
            ctx: TypedActorContext[String],
            msg: String,
            target: BehaviorInterceptor.ReceiveTarget[String]): Behavior[String] = {
          target(ctx, msg.toUpperCase())
        }

      }

      val ref = spawn(Behaviors.intercept(() => toUpper)(testBehavior(pid, probe.ref)))

      ref ! "a"
      ref ! "bc"
      probe.expectMessage("A")
      probe.expectMessage("ABC")
    }

    "be possible to combine with transformMessages" in {
      val probe = createTestProbe[String]()
      val pid = nextPid()
      val ref = spawn(testBehavior(pid, probe.ref).transformMessages[String] {
        case s => s.toUpperCase()
      })

      ref ! "a"
      ref ! "bc"
      probe.expectMessage("A")
      probe.expectMessage("ABC")
    }

    "be possible to combine with MDC" in {
      val probe = createTestProbe[String]()
      val pid = nextPid()
      val ref = spawn(Behaviors.setup[String] { _ =>
        Behaviors
          .withMdc(staticMdc = Map("pid" -> pid), mdcForMessage = (msg: String) => Map("msg" -> msg.toUpperCase())) {
            testBehavior(pid, probe.ref)
          }
      })

      ref ! "a"
      ref ! "bc"
      probe.expectMessage("a")
      probe.expectMessage("abc")

    }

  }
}
