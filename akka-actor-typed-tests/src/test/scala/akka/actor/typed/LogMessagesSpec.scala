/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.actor
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.testkit.EventFilter
import org.scalatest.WordSpecLike
import org.slf4j.MDC
import org.slf4j.event.Level
import org.slf4j.helpers.{ SubstituteLogger, SubstituteLoggerFactory }

class LogMessagesSpec extends ScalaTestWithActorTestKit("""
    akka.loglevel = DEBUG # test verifies debug
    akka.loggers = ["akka.testkit.TestEventListener"]
    """) with WordSpecLike {

  implicit val untyped: actor.ActorSystem = system.toUntyped

  "The log messages behavior" should {

    "log messages and signals" in {

      val factory = new SubstituteLoggerFactory()
      val substituteLogger: SubstituteLogger = factory.getLogger("substitute").asInstanceOf[SubstituteLogger]
      val opts = LogOptions().withLevel(Level.DEBUG).withLogger(substituteLogger)

      val behavior: Behavior[String] = Behaviors.logMessages(opts, Behaviors.ignore)
      val ref: ActorRef[String] = spawn(behavior)

      EventFilter
        .debug(s"actor ${ref.path.toString} received message Hello", source = ref.path.toString, occurrences = 1)
        .intercept(ref ! "Hello", factory.getEventQueue)

      EventFilter
        .debug(s"actor ${ref.path.toString} received signal PostStop", source = ref.path.toString, occurrences = 1)
        .intercept(testKit.stop(ref), factory.getEventQueue)
    }

    "log messages with provided log level" in {
      val factory = new SubstituteLoggerFactory()
      val substituteLogger: SubstituteLogger = factory.getLogger("substitute").asInstanceOf[SubstituteLogger]
      val opts = LogOptions().withLevel(Level.INFO).withLogger(substituteLogger)
      val behavior: Behavior[String] = Behaviors.logMessages(opts, Behaviors.ignore)

      val ref: ActorRef[String] = spawn(behavior)

      EventFilter
        .info(s"actor ${ref.path.toString} received message Hello", source = ref.path.toString, occurrences = 1)
        .intercept(ref ! "Hello", factory.getEventQueue)

      EventFilter
        .info(s"actor ${ref.path.toString} received signal PostStop", source = ref.path.toString, occurrences = 1)
        .intercept(testKit.stop(ref), factory.getEventQueue)
    }

    "log messages with provided logger" in {

      val factory = new SubstituteLoggerFactory()
      val substituteLogger: SubstituteLogger = factory.getLogger("substitute").asInstanceOf[SubstituteLogger]
      val opts = LogOptions().withLevel(Level.DEBUG).withLogger(substituteLogger)
      val behavior: Behavior[String] = Behaviors.logMessages(opts, Behaviors.ignore)

      val ref: ActorRef[String] = spawn(behavior)

      EventFilter
        .debug(s"actor ${ref.path.toString} received message Hello", source = ref.path.toString, occurrences = 1)
        .intercept(ref ! "Hello", factory.getEventQueue)

      EventFilter
        .debug(s"actor ${ref.path.toString} received signal PostStop", source = ref.path.toString, occurrences = 1)
        .intercept(testKit.stop(ref), factory.getEventQueue)
    }

    "not log messages when not enabled" in {
      val factory = new SubstituteLoggerFactory()
      val substituteLogger: SubstituteLogger = factory.getLogger("substitute").asInstanceOf[SubstituteLogger]
      val opts = LogOptions().withLevel(Level.DEBUG).withLogger(substituteLogger).withEnabled(false)
      val behavior: Behavior[String] = Behaviors.logMessages(opts, Behaviors.ignore)

      val ref: ActorRef[String] = spawn(behavior)

      EventFilter
        .debug(s"actor ${ref.path.toString} received message Hello", source = ref.path.toString, occurrences = 0)
        .intercept(ref ! "Hello", factory.getEventQueue)

      EventFilter
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
      val kk = MDC.getMDCAdapter.getCopyOfContextMap()
      EventFilter
        .custom(
          {
            case logEvent if logEvent.getLevel == (Level.TRACE) /*bug slf4j described in akka.actor.typed.testkit.EventFilter line 415)*/ =>
              logEvent.getMessage should ===(s"actor ${ref.path.toString} received message Hello MDC is $mdc")
              true
            case _ =>
              false

          },
          occurrences = 1)
        .intercept(ref ! "Hello", factory.getEventQueue)

      EventFilter
        .debug(
          s"actor ${ref.path.toString} received signal PostStop MDC is $mdc",
          source = ref.path.toString,
          occurrences = 1)
        .intercept(testKit.stop(ref), factory.getEventQueue)
    }

    "log messages of different type" in {
      val factory = new SubstituteLoggerFactory()
      val substituteLogger: SubstituteLogger = factory.getLogger("substitute").asInstanceOf[SubstituteLogger]
      val opts = LogOptions().withLevel(Level.DEBUG).withLogger(substituteLogger)

      val behavior: Behavior[String] = Behaviors.logMessages(opts, Behaviors.ignore[String])

      val ref = spawn(behavior)

      EventFilter
        .debug(s"actor ${ref.path.toString} received message 13", source = ref.path.toString, occurrences = 1)
        .intercept(ref.unsafeUpcast[Any] ! 13, factory.getEventQueue)
    }

  }
}
