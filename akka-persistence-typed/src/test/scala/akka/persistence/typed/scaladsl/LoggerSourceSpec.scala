/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.scaladsl
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.event.Logging.LogEvent
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.RecoveryCompleted
import akka.persistence.typed.SnapshotCompleted
import akka.persistence.typed.SnapshotFailed
import akka.testkit.EventFilter
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpecLike

// Note that the spec name here is important since there are heuristics in place to avoid names
// starting with EventSourcedBehavior
class LoggerSourceSpec
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString("akka.loggers = [akka.testkit.TestEventListener]")
        .withFallback(EventSourcedBehaviorSpec.conf))
    with WordSpecLike {

  implicit val untyped = system.toUntyped // FIXME #24348: eventfilter support in typed testkit

  private val pidCounter = new AtomicInteger(0)
  private def nextPid(): PersistenceId = PersistenceId(s"c${pidCounter.incrementAndGet()})")

  def behavior: Behavior[String] = Behaviors.setup { ctx =>
    ctx.log.info("setting-up-behavior")
    EventSourcedBehavior[String, String, String](nextPid(), emptyState = "", commandHandler = (_, _) => {
      ctx.log.info("command-received")
      Effect.persist("evt")
    }, eventHandler = (state, _) => {
      ctx.log.info("event-received")
      state
    }).receiveSignal {
      case (_, RecoveryCompleted)    => ctx.log.info("recovery-completed")
      case (_, SnapshotCompleted(_)) =>
      case (_, SnapshotFailed(_, _)) =>
    }
  }

  "log from context" should {

    // note that these are somewhat intermingled to make sure no log event from
    // one test case leaks to another, the actual log class is what is tested in each individual case

    "log from setup" in {
      EventFilter.info("recovery-completed", occurrences = 1).intercept {
        eventFilterFor("setting-up-behavior").intercept {
          spawn(behavior)
        }
      }

    }

    "log from recovery completed" in {
      EventFilter.info("setting-up-behavior", occurrences = 1).intercept {
        eventFilterFor("recovery-completed").intercept {
          spawn(behavior)
        }
      }
    }

    "log from command handler" in {
      EventFilter.info(pattern = "(setting-up-behavior|recovery-completed|event-received)", occurrences = 3).intercept {
        eventFilterFor("command-received").intercept {
          spawn(behavior) ! "cmd"
        }
      }
    }

    "log from event handler" in {
      EventFilter
        .info(pattern = "(setting-up-behavior|recovery-completed|command-received)", occurrences = 3)
        .intercept {
          eventFilterFor("event-received").intercept {
            spawn(behavior) ! "cmd"
          }
        }
    }
  }

  def eventFilterFor(logMsg: String) =
    EventFilter.custom(
      {
        case l: LogEvent if l.message == logMsg =>
          if (l.logClass == classOf[LoggerSourceSpec]) true
          else fail(s"Unexpected log source: ${l.logClass} for message ${l.message}")
        case _ => false
      },
      occurrences = 1)

}
