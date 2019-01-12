/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.actor
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.event.Logging
import akka.testkit.EventFilter
import org.scalatest.WordSpecLike

class LogMessagesSpec extends ScalaTestWithActorTestKit("""
    akka.loglevel = DEBUG # test verifies debug
    akka.loggers = ["akka.testkit.TestEventListener"]
    """) with WordSpecLike {

  implicit val untyped: actor.ActorSystem = system.toUntyped

  "The log messages behavior" should {
    "log signals" in {
      val behavior: Behavior[Signal] = Behaviors.logMessages(Behaviors.empty)

      val ref: ActorRef[Signal] = spawn(behavior)

      EventFilter.debug("received signal PostStop", source = ref.path.toString, occurrences = 1).intercept {
        testKit.stop(ref)
      }
    }

    "log messages" in {
      val behavior: Behavior[String] = Behaviors.logMessages(Behaviors.empty)

      val ref: ActorRef[String] = spawn(behavior)

      EventFilter.debug("received message Hello", source = ref.path.toString, occurrences = 1).intercept {
        ref ! "Hello"
      }
    }

    "log messages with provided log level" in {
      val opts = LogOptions().withLevel(Logging.InfoLevel)
      val behavior: Behavior[String] = Behaviors.logMessages(opts, Behaviors.empty)

      val ref: ActorRef[String] = spawn(behavior)

      EventFilter.info("received message Hello", source = ref.path.toString, occurrences = 1).intercept {
        ref ! "Hello"
      }
    }

    "log messages with provided logger" in {
      val logger = system.log
      val opts = LogOptions().withLogger(logger)
      val behavior: Behavior[String] = Behaviors.logMessages(opts, Behaviors.empty)

      val ref: ActorRef[String] = spawn(behavior)

      EventFilter.debug("received message Hello", source = "LogMessagesSpec", occurrences = 1).intercept {
        ref ! "Hello"
      }
    }

    "not log messages when not enabled" in {
      val opts = LogOptions().withEnabled(false)
      val behavior: Behavior[String] = Behaviors.logMessages(opts, Behaviors.empty)

      val ref: ActorRef[String] = spawn(behavior)

      EventFilter.debug("received message Hello", source = ref.path.toString, occurrences = 0).intercept {
        ref ! "Hello"
      }
    }

    "log messages with decorated MDC values" in {
      val behavior = Behaviors.withMdc[String](Map("mdc" -> true))(Behaviors.logMessages(Behaviors.empty))

      val ref = spawn(behavior)
      EventFilter.custom({
        case logEvent if logEvent.level == Logging.DebugLevel ⇒
          logEvent.message should ===("received message Hello")
          logEvent.mdc should ===(Map("mdc" -> true))
          true
        case other ⇒ system.log.error(s"Unexpected log event: {}", other); false
      }, occurrences = 1).intercept {
        ref ! "Hello"
      }
    }
  }
}
