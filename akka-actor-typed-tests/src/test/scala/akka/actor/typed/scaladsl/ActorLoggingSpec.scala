/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.scaladsl

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ Behavior, LogMarker, TestException, TypedAkkaSpec }
import akka.event.Logging
import akka.event.Logging.{ LogEventWithCause, LogEventWithMarker }
import akka.testkit.EventFilter
import akka.testkit.typed.scaladsl.ActorTestKit
import com.typesafe.config.ConfigFactory

class ActorLoggingSpec extends ActorTestKit with TypedAkkaSpec {

  override def config = ConfigFactory.parseString(
    """
    akka.loglevel = DEBUG
    akka.loggers = ["akka.testkit.TestEventListener"]
  """)

  val marker = LogMarker("marker")
  val cause = new TestException("böö")

  implicit val untyped = system.toUntyped

  "Logging in a typed actor" must {

    "be conveniently available from the ctx" in {
      val actor = EventFilter.info("Started", source = "akka://ActorLoggingSpec/user/the-actor", occurrences = 1).intercept {
        spawn(Behaviors.setup[String] { ctx ⇒
          ctx.log.info("Started")

          Behaviors.receive { (ctx, msg) ⇒
            ctx.log.info("got message {}", msg)
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
        spawn(Behaviors.setup[Any] { ctx ⇒
          ctx.log.debug(marker, "whatever")
          ctx.log.info(marker, "whatever")
          ctx.log.warning(marker, "whatever")
          ctx.log.error(marker, "whatever")
          ctx.log.error(marker, cause, "whatever")
          Logging.AllLogLevels.foreach(level ⇒ {
            ctx.log.log(level, marker, "whatever")
          })
          Behaviors.stopped
        })
      )
    }

    "pass cause with warning" in {
      EventFilter.custom({
        case event: LogEventWithCause if event.cause == cause ⇒ true
      }, occurrences = 2).intercept(
        spawn(Behaviors.setup[Any] { ctx ⇒
          ctx.log.warning(cause, "whatever")
          ctx.log.warning(marker, cause, "whatever")
          Behaviors.stopped
        })
      )
    }

    "provide a whole bunch of logging overloads" in {

      // Not the best test but at least it exercises every log overload ;)

      EventFilter.custom({
        case _ ⇒ true // any is fine, we're just after the right count of statements reaching the listener
      }, occurrences = 120).intercept {
        spawn(Behaviors.setup[String] { ctx ⇒
          ctx.log.debug("message")
          ctx.log.debug("{}", "arg1")
          ctx.log.debug("{} {}", "arg1", "arg2")
          ctx.log.debug("{} {} {}", "arg1", "arg2", "arg3")
          ctx.log.debug("{} {} {} {}", "arg1", "arg2", "arg3", "arg4")
          ctx.log.debug("{} {} {} {} {}", Array("arg1", "arg2", "arg3", "arg4", "arg5"))
          ctx.log.debug(marker, "message")
          ctx.log.debug(marker, "{}", "arg1")
          ctx.log.debug(marker, "{} {}", "arg1", "arg2")
          ctx.log.debug(marker, "{} {} {}", "arg1", "arg2", "arg3")
          ctx.log.debug(marker, "{} {} {} {}", "arg1", "arg2", "arg3", "arg4")
          ctx.log.debug(marker, "{} {} {} {} {}", Array("arg1", "arg2", "arg3", "arg4", "arg5"))

          ctx.log.info("message")
          ctx.log.info("{}", "arg1")
          ctx.log.info("{} {}", "arg1", "arg2")
          ctx.log.info("{} {} {}", "arg1", "arg2", "arg3")
          ctx.log.info("{} {} {} {}", "arg1", "arg2", "arg3", "arg4")
          ctx.log.info("{} {} {} {} {}", Array("arg1", "arg2", "arg3", "arg4", "arg5"))
          ctx.log.info(marker, "message")
          ctx.log.info(marker, "{}", "arg1")
          ctx.log.info(marker, "{} {}", "arg1", "arg2")
          ctx.log.info(marker, "{} {} {}", "arg1", "arg2", "arg3")
          ctx.log.info(marker, "{} {} {} {}", "arg1", "arg2", "arg3", "arg4")
          ctx.log.info(marker, "{} {} {} {} {}", Array("arg1", "arg2", "arg3", "arg4", "arg5"))

          ctx.log.warning("message")
          ctx.log.warning("{}", "arg1")
          ctx.log.warning("{} {}", "arg1", "arg2")
          ctx.log.warning("{} {} {}", "arg1", "arg2", "arg3")
          ctx.log.warning("{} {} {} {}", "arg1", "arg2", "arg3", "arg4")
          ctx.log.warning("{} {} {} {} {}", Array("arg1", "arg2", "arg3", "arg4", "arg5"))
          ctx.log.warning(marker, "message")
          ctx.log.warning(marker, "{}", "arg1")
          ctx.log.warning(marker, "{} {}", "arg1", "arg2")
          ctx.log.warning(marker, "{} {} {}", "arg1", "arg2", "arg3")
          ctx.log.warning(marker, "{} {} {} {}", "arg1", "arg2", "arg3", "arg4")
          ctx.log.warning(marker, "{} {} {} {} {}", Array("arg1", "arg2", "arg3", "arg4", "arg5"))

          ctx.log.warning(cause, "message")
          ctx.log.warning(cause, "{}", "arg1")
          ctx.log.warning(cause, "{} {}", "arg1", "arg2")
          ctx.log.warning(cause, "{} {} {}", "arg1", "arg2", "arg3")
          ctx.log.warning(cause, "{} {} {} {}", "arg1", "arg2", "arg3", "arg4")
          ctx.log.warning(cause, "{} {} {} {} {}", Array("arg1", "arg2", "arg3", "arg4", "arg5"))
          ctx.log.warning(marker, cause, "message")
          ctx.log.warning(marker, cause, "{}", "arg1")
          ctx.log.warning(marker, cause, "{} {}", "arg1", "arg2")
          ctx.log.warning(marker, cause, "{} {} {}", "arg1", "arg2", "arg3")
          ctx.log.warning(marker, cause, "{} {} {} {}", "arg1", "arg2", "arg3", "arg4")
          ctx.log.warning(marker, cause, "{} {} {} {} {}", Array("arg1", "arg2", "arg3", "arg4", "arg5"))

          ctx.log.error("message")
          ctx.log.error("{}", "arg1")
          ctx.log.error("{} {}", "arg1", "arg2")
          ctx.log.error("{} {} {}", "arg1", "arg2", "arg3")
          ctx.log.error("{} {} {} {}", "arg1", "arg2", "arg3", "arg4")
          ctx.log.error("{} {} {} {} {}", Array("arg1", "arg2", "arg3", "arg4", "arg5"))
          ctx.log.error(marker, "message")
          ctx.log.error(marker, "{}", "arg1")
          ctx.log.error(marker, "{} {}", "arg1", "arg2")
          ctx.log.error(marker, "{} {} {}", "arg1", "arg2", "arg3")
          ctx.log.error(marker, "{} {} {} {}", "arg1", "arg2", "arg3", "arg4")
          ctx.log.error(marker, "{} {} {} {} {}", Array("arg1", "arg2", "arg3", "arg4", "arg5"))

          ctx.log.error(cause, "message")
          ctx.log.error(cause, "{}", "arg1")
          ctx.log.error(cause, "{} {}", "arg1", "arg2")
          ctx.log.error(cause, "{} {} {}", "arg1", "arg2", "arg3")
          ctx.log.error(cause, "{} {} {} {}", "arg1", "arg2", "arg3", "arg4")
          ctx.log.error(cause, "{} {} {} {} {}", Array("arg1", "arg2", "arg3", "arg4", "arg5"))
          ctx.log.error(marker, cause, "message")
          ctx.log.error(marker, cause, "{}", "arg1")
          ctx.log.error(marker, cause, "{} {}", "arg1", "arg2")
          ctx.log.error(marker, cause, "{} {} {}", "arg1", "arg2", "arg3")
          ctx.log.error(marker, cause, "{} {} {} {}", "arg1", "arg2", "arg3", "arg4")
          ctx.log.error(marker, cause, "{} {} {} {} {}", Array("arg1", "arg2", "arg3", "arg4", "arg5"))

          Logging.AllLogLevels.foreach(level ⇒ {
            ctx.log.log(level, "message")
            ctx.log.log(level, "{}", "arg1")
            ctx.log.log(level, "{} {}", "arg1", "arg2")
            ctx.log.log(level, "{} {} {}", "arg1", "arg2", "arg3")
            ctx.log.log(level, "{} {} {} {}", "arg1", "arg2", "arg3", "arg4")
            ctx.log.log(level, "{} {} {} {} {}", Array("arg1", "arg2", "arg3", "arg4", "arg5"))

            ctx.log.log(level, marker, "message")
            ctx.log.log(level, marker, "{}", "arg1")
            ctx.log.log(level, marker, "{} {}", "arg1", "arg2")
            ctx.log.log(level, marker, "{} {} {}", "arg1", "arg2", "arg3")
            ctx.log.log(level, marker, "{} {} {} {}", "arg1", "arg2", "arg3", "arg4")
            ctx.log.log(level, marker, "{} {} {} {} {}", Array("arg1", "arg2", "arg3", "arg4", "arg5"))
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
        (msg: Protocol) ⇒
          if (msg.transactionId == 1)
            Map(
            "txId" -> msg.transactionId,
            "first" -> true
          )
          else Map("txId" -> msg.transactionId)
      ) {
          Behaviors.setup { ctx ⇒
            ctx.log.info("Starting")
            Behaviors.receiveMessage { msg ⇒
              ctx.log.info("Got message!")
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
            Behaviors.receive { (ctx, msg) ⇒
              ctx.log.info(msg)
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
        Behaviors.receive { (ctx, msg) ⇒
          msg match {
            case "new-behavior" ⇒
              behavior
            case other ⇒
              ctx.log.info(other)
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
          Behaviors.receive { (ctx, msg) ⇒
            msg match {
              case "new-mdc" ⇒
                behavior
              case other ⇒
                ctx.log.info(other)
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
        Behaviors.setup { ctx ⇒
          Behaviors.receiveMessage { msg ⇒
            ctx.log.withMdc(Map("mdc" -> "inner")).info("Got message log.withMDC!")
            // after log.withMdc so we know it didn't change the outer mdc
            ctx.log.info("Got message behavior.withMdc!")
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
