/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.actor
import akka.actor.testkit.typed.scaladsl.LoggingTestKit
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import org.scalatest.WordSpecLike
import org.slf4j.event.Level

class LogMessagesSpec extends ScalaTestWithActorTestKit("""
    akka.loglevel = DEBUG # test verifies debug
    """) with WordSpecLike with LogCapturing {

  implicit val classic: actor.ActorSystem = system.toClassic

  "The log messages behavior" should {

    "log messages and signals" in {
      val behavior: Behavior[String] = Behaviors.logMessages(Behaviors.ignore)

      val ref: ActorRef[String] = spawn(behavior)

      LoggingTestKit.debug(s"actor [${ref.path.toString}] received message: Hello").intercept {
        ref ! "Hello"
      }

      LoggingTestKit.debug(s"actor [${ref.path}] received signal: PostStop").intercept {
        testKit.stop(ref)
      }
    }

    "log messages with provided log level" in {
      val opts = LogOptions().withLevel(Level.INFO)
      val behavior: Behavior[String] = Behaviors.logMessages(opts, Behaviors.ignore)

      val ref: ActorRef[String] = spawn(behavior)

      LoggingTestKit.info(s"actor [${ref.path}] received message: Hello").intercept {
        ref ! "Hello"
      }

      LoggingTestKit.info(s"actor [${ref.path}] received signal: PostStop").intercept {
        testKit.stop(ref)
      }
    }

    "log messages with provided logger" in {
      val logger = system.log
      val opts = LogOptions().withLogger(logger)
      val behavior: Behavior[String] = Behaviors.logMessages(opts, Behaviors.ignore)

      val ref: ActorRef[String] = spawn(behavior)

      LoggingTestKit.debug(s"actor [${ref.path}] received message: Hello").intercept {
        ref ! "Hello"
      }

      LoggingTestKit.debug(s"actor [${ref.path}] received signal: PostStop").intercept {
        testKit.stop(ref)
      }
    }

    "not log messages when not enabled" in {
      val opts = LogOptions().withEnabled(false)
      val behavior: Behavior[String] = Behaviors.logMessages(opts, Behaviors.ignore)

      val ref: ActorRef[String] = spawn(behavior)

      LoggingTestKit.debug(s"actor [${ref.path}] received message: Hello").withOccurrences(0).intercept {
        ref ! "Hello"
      }

      LoggingTestKit.debug(s"actor [${ref.path}] received signal: PostStop").withOccurrences(0).intercept {
        testKit.stop(ref)
      }
    }

    "log messages with decorated MDC values" in {
      val opts = LogOptions().withLevel(Level.DEBUG)
      val mdc = Map("mdc" -> "true")
      val behavior = Behaviors.withMdc[String](mdc)(Behaviors.logMessages(opts, Behaviors.ignore))

      val ref = spawn(behavior)

      LoggingTestKit.debug(s"actor [${ref.path}] received message: Hello").withMdc(mdc).intercept {
        ref ! "Hello"
      }

      LoggingTestKit.debug(s"actor [${ref.path}] received signal: PostStop").withMdc(mdc).intercept {
        testKit.stop(ref)
      }
    }

    "log messages with different decorated MDC values in different actors" in {
      val opts = LogOptions().withLevel(Level.DEBUG)
      val mdc1 = Map("mdc" -> "true")
      val behavior1 = Behaviors.withMdc[String](mdc1)(Behaviors.logMessages(opts, Behaviors.ignore))
      val mdc2 = Map("mdc" -> "false")
      val behavior2 = Behaviors.withMdc[String](mdc2)(Behaviors.logMessages(opts, Behaviors.ignore))

      val ref2 = spawn(behavior2)

      LoggingTestKit.debug(s"actor [${ref2.path}] received message: Hello").withMdc(mdc2).intercept {
        ref2 ! "Hello"
      }

      val ref1 = spawn(behavior1)

      LoggingTestKit.debug(s"actor [${ref1.path}] received message: Hello").withMdc(mdc1).intercept {
        ref1 ! "Hello"
      }

      LoggingTestKit.debug(s"actor [${ref2.path}] received signal: PostStop").withMdc(mdc2).intercept {
        testKit.stop(ref2)
      }

      LoggingTestKit.debug(s"actor [${ref1.path}] received signal: PostStop").withMdc(mdc1).intercept {
        testKit.stop(ref1)
      }
    }

    "log messages of different type" in {
      val behavior: Behavior[String] = Behaviors.logMessages(Behaviors.ignore[String])

      val ref = spawn(behavior)

      LoggingTestKit.debug(s"actor [${ref.path}] received message: 13").intercept {
        ref.unsafeUpcast[Any] ! 13
      }
    }

  }
}
