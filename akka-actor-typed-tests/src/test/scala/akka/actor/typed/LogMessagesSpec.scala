/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.actor
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.testkit.LoggingEventFilter
import org.scalatest.WordSpecLike
import org.slf4j.{ Logger, LoggerFactory, MDC }
import org.slf4j.event.Level
import org.slf4j.helpers.{ SubstituteLogger, SubstituteLoggerFactory }

//TODO review akka.testkit.TestEventListener as config. Has quite important implications

class LogMessagesSpec extends ScalaTestWithActorTestKit("""
    akka.loglevel = DEBUG # test verifies debug
    akka.loggers = ["akka.actor.typed.testkit.TestEventListener"]
    """) with WordSpecLike {

  implicit val untyped: actor.ActorSystem = system.toUntyped

  "The log messages behavior" should {

    "log messages and signals" in {

      val factory = new SubstituteLoggerFactory()
      val substituteLogger: SubstituteLogger = factory.getLogger("substitute").asInstanceOf[SubstituteLogger]
      val opts = LogOptions().withLevel(Level.DEBUG).withLogger(substituteLogger)

      val behavior: Behavior[String] = Behaviors.logMessages(opts, Behaviors.ignore)
      val ref: ActorRef[String] = spawn(behavior)

      LoggingEventFilter
        .debug(s"actor ${ref.path.toString} received message Hello", source = ref.path.toString, occurrences = 1)
        .intercept(ref ! "Hello", factory.getEventQueue)

      LoggingEventFilter
        .debug(s"actor ${ref.path.toString} received signal PostStop", source = ref.path.toString, occurrences = 1)
        .intercept(testKit.stop(ref), factory.getEventQueue)
    }

    "log messages with provided log level" in {
      val factory = new SubstituteLoggerFactory()
      val substituteLogger: SubstituteLogger = factory.getLogger("substitute").asInstanceOf[SubstituteLogger]
      val opts = LogOptions().withLevel(Level.INFO).withLogger(substituteLogger)
      val behavior: Behavior[String] = Behaviors.logMessages(opts, Behaviors.ignore)

      val ref: ActorRef[String] = spawn(behavior)

      LoggingEventFilter
        .info(s"actor ${ref.path.toString} received message Hello", source = ref.path.toString, occurrences = 1)
        .intercept(ref ! "Hello", factory.getEventQueue)

      LoggingEventFilter
        .info(s"actor ${ref.path.toString} received signal PostStop", source = ref.path.toString, occurrences = 1)
        .intercept(testKit.stop(ref), factory.getEventQueue)
    }

    "log messages with provided logger" in {

      val factory = new SubstituteLoggerFactory()
      val substituteLogger: SubstituteLogger = factory.getLogger("substitute").asInstanceOf[SubstituteLogger]
      val opts = LogOptions().withLevel(Level.DEBUG).withLogger(substituteLogger)
      val behavior: Behavior[String] = Behaviors.logMessages(opts, Behaviors.ignore)

      val ref: ActorRef[String] = spawn(behavior)

      LoggingEventFilter
        .debug(s"actor ${ref.path.toString} received message Hello", source = ref.path.toString, occurrences = 1)
        .intercept(ref ! "Hello", factory.getEventQueue)

      LoggingEventFilter
        .debug(s"actor ${ref.path.toString} received signal PostStop", source = ref.path.toString, occurrences = 1)
        .intercept(testKit.stop(ref), factory.getEventQueue)
    }

    "not log messages when not enabled" in {
      val factory = new SubstituteLoggerFactory()
      val substituteLogger: SubstituteLogger = factory.getLogger("substitute").asInstanceOf[SubstituteLogger]
      substituteLogger.setDelegate(LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME))

      val opts = LogOptions().withLevel(Level.DEBUG).withLogger(substituteLogger).withEnabled(false)
      val behavior: Behavior[String] = Behaviors.logMessages(opts, Behaviors.ignore)

      val ref: ActorRef[String] = spawn(behavior)

      LoggingEventFilter
        .debug(s"actor ${ref.path.toString} received message Hello", source = ref.path.toString, occurrences = 0)
        .intercept(ref ! "Hello", factory.getEventQueue)

      LoggingEventFilter
        .debug(s"actor ${ref.path.toString} received signal PostStop", source = ref.path.toString, occurrences = 0)
        .intercept(testKit.stop(ref), factory.getEventQueue)
    }

    "log messages with decorated MDC values" in {
      val factory = new SubstituteLoggerFactory()
      val substituteLogger: SubstituteLogger = factory.getLogger("substitute").asInstanceOf[SubstituteLogger]

      val opts = LogOptions().withLevel(Level.DEBUG).withLogger(substituteLogger)
      val mdc = Map("mdc" -> "true")
      val behavior = Behaviors.withMdc[String](mdc)(Behaviors.logMessages(opts, Behaviors.ignore))

      val ref = spawn(behavior)
      LoggingEventFilter
        .debug(
          s"actor ${ref.path.toString} received message Hello MDC is $mdc",
          source = ref.path.toString,
          occurrences = 1)
        .intercept(ref ! "Hello", factory.getEventQueue)

      LoggingEventFilter
        .debug(
          s"actor ${ref.path.toString} received signal PostStop MDC is $mdc",
          source = ref.path.toString,
          occurrences = 1)
        .intercept(testKit.stop(ref), factory.getEventQueue)
    }

    "log messages with different decorated MDC values in different actors" in {
      val factory = new SubstituteLoggerFactory()
      val substituteLogger: SubstituteLogger = factory.getLogger("substitute").asInstanceOf[SubstituteLogger]

      val opts = LogOptions().withLevel(Level.DEBUG).withLogger(substituteLogger)
      val mdc1 = Map("mdc" -> "true")
      val behavior1 = Behaviors.withMdc[String](mdc1)(Behaviors.logMessages(opts, Behaviors.ignore))
      val mdc2 = Map("mdc" -> "false")
      val behavior2 = Behaviors.withMdc[String](mdc2)(Behaviors.logMessages(opts, Behaviors.ignore))

      val ref2 = spawn(behavior2)
      LoggingEventFilter
        .debug(
          s"actor ${ref2.path.toString} received message Hello MDC is $mdc2",
          source = ref2.path.toString,
          occurrences = 1)
        .intercept(ref2 ! "Hello", factory.getEventQueue)

      val ref1 = spawn(behavior1)
      LoggingEventFilter
        .debug(
          s"actor ${ref1.path.toString} received message Hello MDC is $mdc1",
          source = ref1.path.toString,
          occurrences = 1)
        .intercept(ref1 ! "Hello", factory.getEventQueue)

      LoggingEventFilter
        .debug(
          s"actor ${ref2.path.toString} received signal PostStop MDC is $mdc2",
          source = ref2.path.toString,
          occurrences = 1)
        .intercept(testKit.stop(ref2), factory.getEventQueue)

      LoggingEventFilter
        .debug(
          s"actor ${ref1.path.toString} received signal PostStop MDC is $mdc1",
          source = ref1.path.toString,
          occurrences = 1)
        .intercept(testKit.stop(ref1), factory.getEventQueue)

    }

    "log messages of different type" in {
      val factory = new SubstituteLoggerFactory()
      val substituteLogger: Logger = factory.getLogger("substitute").asInstanceOf[SubstituteLogger]
      val opts = LogOptions().withLevel(Level.DEBUG).withLogger(substituteLogger)

      val behavior: Behavior[String] = Behaviors.logMessages(opts, Behaviors.ignore[String])

      val ref = spawn(behavior)

      LoggingEventFilter
        .debug(s"actor ${ref.path.toString} received message 13", source = ref.path.toString, occurrences = 1)
        .intercept(ref.unsafeUpcast[Any] ! 13, factory.getEventQueue)
    }

  }
}
