/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor

import akka.testkit.AkkaSpec
import akka.testkit.EventFilter
import akka.testkit.ImplicitSender
import akka.testkit.TestActors

class DeadLetterSuspensionSpec extends AkkaSpec("""
  akka.loglevel = INFO
  akka.log-dead-letters = 3
  akka.log-dead-letters-suspend-duration = 2s
  """) with ImplicitSender {

  val deadActor = system.actorOf(TestActors.echoActorProps)
  watch(deadActor)
  deadActor ! PoisonPill
  expectTerminated(deadActor)

  private def expectedDeadLettersLogMessage(count: Int): String =
    s"Message [java.lang.Integer] from $testActor to $deadActor was not delivered. [$count] dead letters encountered"

  "must suspend dead-letters logging when reaching 'akka.log-dead-letters', and then re-enable" in {

    EventFilter.info(start = expectedDeadLettersLogMessage(1), occurrences = 1).intercept {
      deadActor ! 1
    }
    EventFilter.info(start = expectedDeadLettersLogMessage(2), occurrences = 1).intercept {
      deadActor ! 2
    }
    EventFilter
      .info(start = expectedDeadLettersLogMessage(3) + ", no more dead letters will be logged in next", occurrences = 1)
      .intercept {
        deadActor ! 3
      }
    deadActor ! 4
    deadActor ! 5

    // let suspend-duration elapse
    Thread.sleep(2050)

    // re-enabled
    EventFilter
      .info(start = expectedDeadLettersLogMessage(6) + ", of which 2 were not logged", occurrences = 1)
      .intercept {
        deadActor ! 6
      }

    // reset count
    EventFilter.info(start = expectedDeadLettersLogMessage(1), occurrences = 1).intercept {
      deadActor ! 7
    }

  }

}
