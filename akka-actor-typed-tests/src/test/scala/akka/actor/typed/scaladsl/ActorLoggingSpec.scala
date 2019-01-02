/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.scaladsl

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.testkit.typed.TestException
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ Behavior, LogMarker }
import akka.event.Logging
import akka.event.Logging.{ LogEventWithCause, LogEventWithMarker }
import akka.testkit.EventFilter
import org.scalatest.WordSpecLike

class ActorLoggingSpec extends ScalaTestWithActorTestKit("""
    akka.loglevel = DEBUG # test verifies debug
    akka.loggers = ["akka.testkit.TestEventListener"]
    """) with WordSpecLike {

  val marker = LogMarker("marker")
  val cause = new TestException("böö")

  implicit val untyped = system.toUntyped

  "Logging in a typed actor" must {

    "be conveniently available from the context" in {
      val actor = EventFilter.info("Started", source = "akka://ActorLoggingSpec/user/the-actor", occurrences = 1).intercept {
        spawn(Behaviors.setup[String] { context ⇒
          context.log.info("Started")

          Behaviors.receive { (context, message) ⇒
            context.log.info("got message {}", message)
            Behaviors.same
          }
        }, "the-actor")
      }

      EventFilter.info("got message Hello", source = "akka://ActorLoggingSpec/user/the-actor", occurrences = 1).intercept {
        actor ! "Hello"
      }
    }

    "pass markers to the log" in {
      EventFilter.custom({
        case event: LogEventWithMarker if event.marker == marker ⇒ true
      }, occurrences = 9).intercept(
        spawn(Behaviors.setup[Any] { context ⇒
          context.log.debug(marker, "whatever")
          context.log.info(marker, "whatever")
          context.log.warning(marker, "whatever")
          context.log.error(marker, "whatever")
          context.log.error(marker, cause, "whatever")
          Logging.AllLogLevels.foreach(level ⇒ {
            context.log.log(level, marker, "whatever")
          })
          Behaviors.stopped
        })
      )
    }

    "pass cause with warning" in {
      EventFilter.custom({
        case event: LogEventWithCause if event.cause == cause ⇒ true
      }, occurrences = 2).intercept(
        spawn(Behaviors.setup[Any] { context ⇒
          context.log.warning(cause, "whatever")
          context.log.warning(marker, cause, "whatever")
          Behaviors.stopped
        })
      )
    }

    "provide a whole bunch of logging overloads" in {

      // Not the best test but at least it exercises every log overload ;)

      EventFilter.custom({
        case _ ⇒ true // any is fine, we're just after the right count of statements reaching the listener
      }, occurrences = 120).intercept {
        spawn(Behaviors.setup[String] { context ⇒
          context.log.debug("message")
          context.log.debug("{}", "arg1")
          context.log.debug("{} {}", "arg1", "arg2")
          context.log.debug("{} {} {}", "arg1", "arg2", "arg3")
          context.log.debug("{} {} {} {}", "arg1", "arg2", "arg3", "arg4")
          context.log.debug("{} {} {} {} {}", Array("arg1", "arg2", "arg3", "arg4", "arg5"))
          context.log.debug(marker, "message")
          context.log.debug(marker, "{}", "arg1")
          context.log.debug(marker, "{} {}", "arg1", "arg2")
          context.log.debug(marker, "{} {} {}", "arg1", "arg2", "arg3")
          context.log.debug(marker, "{} {} {} {}", "arg1", "arg2", "arg3", "arg4")
          context.log.debug(marker, "{} {} {} {} {}", Array("arg1", "arg2", "arg3", "arg4", "arg5"))

          context.log.info("message")
          context.log.info("{}", "arg1")
          context.log.info("{} {}", "arg1", "arg2")
          context.log.info("{} {} {}", "arg1", "arg2", "arg3")
          context.log.info("{} {} {} {}", "arg1", "arg2", "arg3", "arg4")
          context.log.info("{} {} {} {} {}", Array("arg1", "arg2", "arg3", "arg4", "arg5"))
          context.log.info(marker, "message")
          context.log.info(marker, "{}", "arg1")
          context.log.info(marker, "{} {}", "arg1", "arg2")
          context.log.info(marker, "{} {} {}", "arg1", "arg2", "arg3")
          context.log.info(marker, "{} {} {} {}", "arg1", "arg2", "arg3", "arg4")
          context.log.info(marker, "{} {} {} {} {}", Array("arg1", "arg2", "arg3", "arg4", "arg5"))

          context.log.warning("message")
          context.log.warning("{}", "arg1")
          context.log.warning("{} {}", "arg1", "arg2")
          context.log.warning("{} {} {}", "arg1", "arg2", "arg3")
          context.log.warning("{} {} {} {}", "arg1", "arg2", "arg3", "arg4")
          context.log.warning("{} {} {} {} {}", Array("arg1", "arg2", "arg3", "arg4", "arg5"))
          context.log.warning(marker, "message")
          context.log.warning(marker, "{}", "arg1")
          context.log.warning(marker, "{} {}", "arg1", "arg2")
          context.log.warning(marker, "{} {} {}", "arg1", "arg2", "arg3")
          context.log.warning(marker, "{} {} {} {}", "arg1", "arg2", "arg3", "arg4")
          context.log.warning(marker, "{} {} {} {} {}", Array("arg1", "arg2", "arg3", "arg4", "arg5"))

          context.log.warning(cause, "message")
          context.log.warning(cause, "{}", "arg1")
          context.log.warning(cause, "{} {}", "arg1", "arg2")
          context.log.warning(cause, "{} {} {}", "arg1", "arg2", "arg3")
          context.log.warning(cause, "{} {} {} {}", "arg1", "arg2", "arg3", "arg4")
          context.log.warning(cause, "{} {} {} {} {}", Array("arg1", "arg2", "arg3", "arg4", "arg5"))
          context.log.warning(marker, cause, "message")
          context.log.warning(marker, cause, "{}", "arg1")
          context.log.warning(marker, cause, "{} {}", "arg1", "arg2")
          context.log.warning(marker, cause, "{} {} {}", "arg1", "arg2", "arg3")
          context.log.warning(marker, cause, "{} {} {} {}", "arg1", "arg2", "arg3", "arg4")
          context.log.warning(marker, cause, "{} {} {} {} {}", Array("arg1", "arg2", "arg3", "arg4", "arg5"))

          context.log.error("message")
          context.log.error("{}", "arg1")
          context.log.error("{} {}", "arg1", "arg2")
          context.log.error("{} {} {}", "arg1", "arg2", "arg3")
          context.log.error("{} {} {} {}", "arg1", "arg2", "arg3", "arg4")
          context.log.error("{} {} {} {} {}", Array("arg1", "arg2", "arg3", "arg4", "arg5"))
          context.log.error(marker, "message")
          context.log.error(marker, "{}", "arg1")
          context.log.error(marker, "{} {}", "arg1", "arg2")
          context.log.error(marker, "{} {} {}", "arg1", "arg2", "arg3")
          context.log.error(marker, "{} {} {} {}", "arg1", "arg2", "arg3", "arg4")
          context.log.error(marker, "{} {} {} {} {}", Array("arg1", "arg2", "arg3", "arg4", "arg5"))

          context.log.error(cause, "message")
          context.log.error(cause, "{}", "arg1")
          context.log.error(cause, "{} {}", "arg1", "arg2")
          context.log.error(cause, "{} {} {}", "arg1", "arg2", "arg3")
          context.log.error(cause, "{} {} {} {}", "arg1", "arg2", "arg3", "arg4")
          context.log.error(cause, "{} {} {} {} {}", Array("arg1", "arg2", "arg3", "arg4", "arg5"))
          context.log.error(marker, cause, "message")
          context.log.error(marker, cause, "{}", "arg1")
          context.log.error(marker, cause, "{} {}", "arg1", "arg2")
          context.log.error(marker, cause, "{} {} {}", "arg1", "arg2", "arg3")
          context.log.error(marker, cause, "{} {} {} {}", "arg1", "arg2", "arg3", "arg4")
          context.log.error(marker, cause, "{} {} {} {} {}", Array("arg1", "arg2", "arg3", "arg4", "arg5"))

          Logging.AllLogLevels.foreach(level ⇒ {
            context.log.log(level, "message")
            context.log.log(level, "{}", "arg1")
            context.log.log(level, "{} {}", "arg1", "arg2")
            context.log.log(level, "{} {} {}", "arg1", "arg2", "arg3")
            context.log.log(level, "{} {} {} {}", "arg1", "arg2", "arg3", "arg4")
            context.log.log(level, "{} {} {} {} {}", Array("arg1", "arg2", "arg3", "arg4", "arg5"))

            context.log.log(level, marker, "message")
            context.log.log(level, marker, "{}", "arg1")
            context.log.log(level, marker, "{} {}", "arg1", "arg2")
            context.log.log(level, marker, "{} {} {}", "arg1", "arg2", "arg3")
            context.log.log(level, marker, "{} {} {} {}", "arg1", "arg2", "arg3", "arg4")
            context.log.log(level, marker, "{} {} {} {} {}", Array("arg1", "arg2", "arg3", "arg4", "arg5"))
          })

          Behaviors.stopped
        })
      }
    }

  }

  trait Protocol {
    def transactionId: Long
  }
  case class Message(transactionId: Long, message: String) extends Protocol

  "Logging with MDC for a typed actor" must {

    "provide the MDC values in the log" in {
      val behaviors = Behaviors.withMdc[Protocol](
        Map("static" -> 1),
        // FIXME why u no infer the type here Scala??
        (message: Protocol) ⇒
          if (message.transactionId == 1)
            Map(
            "txId" -> message.transactionId,
            "first" -> true
          )
          else Map("txId" -> message.transactionId)
      ) {
          Behaviors.setup { context ⇒
            context.log.info("Starting")
            Behaviors.receiveMessage { message ⇒
              context.log.info("Got message!")
              Behaviors.same
            }
          }
        }

      // mdc on defer is empty (thread and timestamp MDC is added by logger backend)
      val ref = EventFilter.custom({
        case logEvent if logEvent.level == Logging.InfoLevel ⇒
          logEvent.message should ===("Starting")
          logEvent.mdc shouldBe empty
          true
        case other ⇒ system.log.error(s"Unexpected log event: {}", other); false
      }, occurrences = 1).intercept {
        spawn(behaviors)
      }

      // mdc on message
      EventFilter.custom({
        case logEvent if logEvent.level == Logging.InfoLevel ⇒
          logEvent.message should ===("Got message!")
          logEvent.mdc should ===(Map("static" -> 1, "txId" -> 1L, "first" -> true))
          true
        case other ⇒ system.log.error(s"Unexpected log event: {}", other); false
      }, occurrences = 1).intercept {
        ref ! Message(1, "first")
      }

      // mdc does not leak between messages
      EventFilter.custom({
        case logEvent if logEvent.level == Logging.InfoLevel ⇒
          logEvent.message should ===("Got message!")
          logEvent.mdc should ===(Map("static" -> 1, "txId" -> 2L))
          true
        case other ⇒ system.log.error(s"Unexpected log event: {}", other); false
      }, occurrences = 1).intercept {
        ref ! Message(2, "second")
      }
    }

    "use the outermost initial mdc" in {
      // when we declare it, we expect the outermost to win
      val behavior =
        Behaviors.withMdc[String](Map("outermost" -> true)) {
          Behaviors.withMdc(Map("innermost" -> true)) {
            Behaviors.receive { (context, message) ⇒
              context.log.info(message)
              Behaviors.same
            }
          }
        }

      val ref = spawn(behavior)
      EventFilter.custom({
        case logEvent if logEvent.level == Logging.InfoLevel ⇒
          logEvent.message should ===("message")
          logEvent.mdc should ===(Map("outermost" -> true))
          true
        case other ⇒ system.log.error(s"Unexpected log event: {}", other); false
      }, occurrences = 1).intercept {
        ref ! "message"
      }
    }

    "keep being applied when behavior changes to other behavior" in {
      def behavior: Behavior[String] =
        Behaviors.receive { (context, message) ⇒
          message match {
            case "new-behavior" ⇒
              behavior
            case other ⇒
              context.log.info(other)
              Behaviors.same
          }
        }

      val ref = spawn(Behaviors.withMdc(Map("hasMdc" -> true))(behavior))
      EventFilter.custom({
        case logEvent if logEvent.level == Logging.InfoLevel ⇒
          logEvent.message should ===("message")
          logEvent.mdc should ===(Map("hasMdc" -> true))
          true
        case other ⇒ system.log.error(s"Unexpected log event: {}", other); false
      }, occurrences = 1).intercept {
        ref ! "message"
      }

      ref ! "new-behavior"

      EventFilter.custom({
        case logEvent if logEvent.level == Logging.InfoLevel ⇒
          logEvent.message should ===("message")
          logEvent.mdc should ===(Map("hasMdc" -> true)) // original mdc should stay
          true
        case other ⇒ system.log.error(s"Unexpected log event: {}", other); false
      }, occurrences = 1).intercept {
        ref ! "message"
      }

    }

    "replace when behavior changes to other behavior wrapped in withMdc" in {
      // when it changes while running, we expect the latest one to apply
      val id = new AtomicInteger(0)
      def behavior: Behavior[String] =
        Behaviors.withMdc(Map("mdc-version" -> id.incrementAndGet())) {
          Behaviors.receive { (context, message) ⇒
            message match {
              case "new-mdc" ⇒
                behavior
              case other ⇒
                context.log.info(other)
                Behaviors.same
            }
          }
        }

      val ref = spawn(behavior)
      EventFilter.custom({
        case logEvent if logEvent.level == Logging.InfoLevel ⇒
          logEvent.message should ===("message")
          logEvent.mdc should ===(Map("mdc-version" -> 1))
          true
        case other ⇒ system.log.error(s"Unexpected log event: {}", other); false
      }, occurrences = 1).intercept {
        ref ! "message"
      }
      ref ! "new-mdc"
      EventFilter.custom({
        case logEvent if logEvent.level == Logging.InfoLevel ⇒
          logEvent.message should ===("message")
          logEvent.mdc should ===(Map("mdc-version" -> 2)) // mdc should have been replaced
          true
        case other ⇒ system.log.error(s"Unexpected log event: {}", other); false
      }, occurrences = 1).intercept {
        ref ! "message"
      }

    }

    "provide a withMdc decorator" in {
      val behavior = Behaviors.withMdc[Protocol](Map("mdc" -> "outer"))(
        Behaviors.setup { context ⇒
          Behaviors.receiveMessage { message ⇒
            context.log.withMdc(Map("mdc" -> "inner")).info("Got message log.withMDC!")
            // after log.withMdc so we know it didn't change the outer mdc
            context.log.info("Got message behavior.withMdc!")
            Behaviors.same
          }
        }
      )

      // mdc on message
      val ref = spawn(behavior)
      EventFilter.custom({
        case logEvent if logEvent.level == Logging.InfoLevel ⇒
          logEvent.message should ===("Got message behavior.withMdc!")
          logEvent.mdc should ===(Map("mdc" -> "outer"))
          true
        case other ⇒ system.log.error(s"Unexpected log event: {}", other); false
      }, occurrences = 1).intercept {
        EventFilter.custom({
          case logEvent if logEvent.level == Logging.InfoLevel ⇒
            logEvent.message should ===("Got message log.withMDC!")
            logEvent.mdc should ===(Map("mdc" -> "inner"))
            true
          case other ⇒ system.log.error(s"Unexpected log event: {}", other); false
        }, occurrences = 1).intercept {
          ref ! Message(1, "first")
        }
      }
    }

  }

}
