/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.scaladsl

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.testkit.typed.TestException
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.adapter._
import akka.event.Logging
import akka.event.Logging.{ LogEvent, LogEventWithCause, LogEventWithMarker }
import akka.testkit.EventFilter
import org.scalatest.WordSpecLike
import org.slf4j.helpers.BasicMarkerFactory

class SomeClass

object WhereTheBehaviorIsDefined {

  def behavior: Behavior[String] = Behaviors.setup { context =>
    context.log.info("Starting up")
    Behaviors.stopped
  }

}

object BehaviorWhereTheLoggerIsUsed {
  def behavior: Behavior[String] = Behaviors.setup(ctx => new BehaviorWhereTheLoggerIsUsed(ctx))
}
class BehaviorWhereTheLoggerIsUsed(context: ActorContext[String]) extends AbstractBehavior[String] {
  context.log.info("Starting up")
  override def onMessage(msg: String): Behavior[String] = {
    Behaviors.same
  }
}

class ActorLoggingSpec extends ScalaTestWithActorTestKit("""
    akka.loglevel = DEBUG # test verifies debug
    akka.loggers = ["akka.testkit.TestEventListener"]
    """) with WordSpecLike {

  val marker = new BasicMarkerFactory().getMarker("marker")
  val cause = new TestException("böö")

  implicit val untyped = system.toUntyped

  "Logging in a typed actor" must {

    "be conveniently available from the context" in {
      val actor =
        EventFilter.info("Started", source = "akka://ActorLoggingSpec/user/the-actor", occurrences = 1).intercept {
          spawn(
            Behaviors.setup[String] { context =>
              context.log.info("Started")

              Behaviors.receive { (context, message) =>
                context.log.info("got message {}", message)
                Behaviors.same
              }
            },
            "the-actor")
        }

      EventFilter
        .info("got message Hello", source = "akka://ActorLoggingSpec/user/the-actor", occurrences = 1)
        .intercept {
          actor ! "Hello"
        }
    }

    "contain the class name where the first log was called" in {
      val eventFilter = EventFilter.custom({
        case l: LogEvent if l.logClass == classOf[ActorLoggingSpec] => true
        case l: LogEvent =>
          println(l.logClass)
          false
      }, occurrences = 1)

      eventFilter.intercept {
        spawn(Behaviors.setup[String] { context =>
          context.log.info("Started")

          Behaviors.receive { (context, message) =>
            context.log.info("got message {}", message)
            Behaviors.same
          }
        }, "the-actor-with-class")
      }
    }

    "contain the object class name where the first log was called" in {
      val eventFilter = EventFilter.custom({
        case l: LogEvent if l.logClass == WhereTheBehaviorIsDefined.getClass => true
        case l: LogEvent =>
          println(l.logClass)
          false
      }, occurrences = 1)

      eventFilter.intercept {
        spawn(WhereTheBehaviorIsDefined.behavior, "the-actor-with-object")
      }
    }

    "contain the abstract behavior class name where the first log was called" in {
      val eventFilter = EventFilter.custom({
        case l: LogEvent if l.logClass == classOf[BehaviorWhereTheLoggerIsUsed] => true
        case l: LogEvent =>
          println(l.logClass)
          false
      }, occurrences = 1)

      eventFilter.intercept {
        spawn(BehaviorWhereTheLoggerIsUsed.behavior, "the-actor-with-behavior")
      }
    }

    "allow for adapting log source and class" in {
      val eventFilter = EventFilter.custom({
        case l: LogEvent =>
          l.logClass == classOf[SomeClass] &&
          l.logSource == "who-knows-where-it-came-from" &&
          l.mdc == Map("mdc" -> true) // mdc should be kept
      }, occurrences = 1)

      eventFilter.intercept {
        spawn(Behaviors.setup[String] { context =>
          context.log.info("Started")
          Behaviors.empty
        }, "the-actor-with-custom-class")
      }
    }

    "pass markers to the log" in {
      EventFilter
        .custom({
          case event: LogEventWithMarker if event.marker == marker => true
        }, occurrences = 9)
        .intercept(spawn(Behaviors.setup[Any] { context =>
          context.log.debug(marker, "whatever")
          context.log.info(marker, "whatever")
          context.log.warn(marker, "whatever")
          context.log.error(marker, "whatever")
          context.log.error(marker, "whatever", cause)
          Behaviors.stopped
        }))
    }

    "pass cause with warn" in {
      EventFilter
        .custom({
          case event: LogEventWithCause if event.cause == cause => true
        }, occurrences = 2)
        .intercept(spawn(Behaviors.setup[Any] { context =>
          context.log.warn("whatever", cause)
          context.log.warn(marker, "whatever", cause)
          Behaviors.stopped
        }))
    }

    "provide a whole bunch of logging overloads" in {

      // Not the best test but at least it exercises every log overload ;)

      EventFilter
        .custom({
          case _ => true // any is fine, we're just after the right count of statements reaching the listener
        }, occurrences = 120)
        .intercept {
          spawn(Behaviors.setup[String] { context =>
            context.log.debug("message")
            context.log.debug("{}", "arg1")
            context.log.debug("{} {}", 1, 2) //using Int to avoid ambiguous reference to overloaded definition
            context.log.debug("{} {} {}", "arg1", "arg2", "arg3")
            context.log.debug(marker, "message")
            context.log.debug(marker, "{}", "arg1")
            context.log.debug(marker, "{} {}", 1, 2) //using Int to avoid ambiguous reference to overloaded definition
            context.log.debug(marker, "{} {} {}", Array("arg1", "arg2", "arg3"):_*)

            context.log.info("message")
            context.log.info("{}", "arg1")
            context.log.info("{} {}", 1, 2)
            context.log.info("{} {} {}", Array("arg1", "arg2", "arg3"):_*)
            context.log.info(marker, "message")
            context.log.info(marker, "{}", "arg1")
            context.log.info(marker, "{} {}", 1, 2)
            context.log.info(marker, "{} {} {}", Array("arg1", "arg2", "arg3"):_*)

            context.log.warn("message")
            context.log.warn("{}", "arg1")
            context.log.warn("{} {}", 1,2)
            context.log.warn("{} {} {}", Array("arg1", "arg2", "arg3"):_*)
            context.log.warn(marker, "message")
            context.log.warn(marker, "{}", "arg1")
            context.log.warn(marker, "{} {}", 1, 2)
            context.log.warn(marker, "{} {} {}", Array("arg1", "arg2", "arg3"):_*)
            context.log.warn("message",cause)

            context.log.error("message")
            context.log.error("{}", "arg1")
            context.log.error("{} {}", 1,2)
            context.log.error("{} {} {}", Array("arg1", "arg2", "arg3"):_*)
            context.log.error(marker, "message")
            context.log.error(marker, "{}", "arg1")
            context.log.error(marker, "{} {}", 1, 2)
            context.log.error(marker, "{} {} {}", Array("arg1", "arg2", "arg3"):_*)
            context.log.error("message",cause)

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
        Map("static" -> "1"),
        // FIXME why u no infer the type here Scala??
        //TODO review that change from Map[String,Any] to Map[String,String] is viable
        (message: Protocol) =>
          if (message.transactionId == 1)
            Map("txId" -> message.transactionId.toString, "first" -> "true")
          else Map("txId" -> message.transactionId.toString)) {
        Behaviors.setup { context =>
          context.log.info("Starting")
          Behaviors.receiveMessage { _ =>
            context.log.info("Got message!")
            Behaviors.same
          }
        }
      }

      // mdc on defer is empty (thread and timestamp MDC is added by logger backend)
      val ref = EventFilter
        .custom(
          {
            case logEvent if logEvent.level == Logging.InfoLevel =>
              logEvent.message should ===("Starting")
              logEvent.mdc shouldBe empty
              true
            case other => system.log.error(s"Unexpected log event: {}", other); false
          },
          occurrences = 1)
        .intercept {
          spawn(behaviors)
        }

      // mdc on message
      EventFilter
        .custom(
          {
            case logEvent if logEvent.level == Logging.InfoLevel =>
              logEvent.message should ===("Got message!")
              logEvent.mdc should ===(Map("static" -> 1, "txId" -> 1L, "first" -> true))
              true
            case other => system.log.error(s"Unexpected log event: {}", other); false
          },
          occurrences = 1)
        .intercept {
          ref ! Message(1, "first")
        }

      // mdc does not leak between messages
      EventFilter
        .custom(
          {
            case logEvent if logEvent.level == Logging.InfoLevel =>
              logEvent.message should ===("Got message!")
              logEvent.mdc should ===(Map("static" -> 1, "txId" -> 2L))
              true
            case other => system.log.error(s"Unexpected log event: {}", other); false
          },
          occurrences = 1)
        .intercept {
          ref ! Message(2, "second")
        }
    }

    "use the outermost initial mdc" in {
      // when we declare it, we expect the outermost to win
      val behavior =
        Behaviors.withMdc[String](Map("outermost" -> "true")) {
          Behaviors.withMdc(Map("innermost" -> "true")) {
            Behaviors.receive { (context, message) =>
              context.log.info(message)
              Behaviors.same
            }
          }
        }

      val ref = spawn(behavior)
      EventFilter
        .custom(
          {
            case logEvent if logEvent.level == Logging.InfoLevel =>
              logEvent.message should ===("message")
              logEvent.mdc should ===(Map("outermost" -> true))
              true
            case other => system.log.error(s"Unexpected log event: {}", other); false
          },
          occurrences = 1)
        .intercept {
          ref ! "message"
        }
    }

    "keep being applied when behavior changes to other behavior" in {
      def behavior: Behavior[String] =
        Behaviors.receive { (context, message) =>
          message match {
            case "new-behavior" =>
              behavior
            case other =>
              context.log.info(other)
              Behaviors.same
          }
        }

      val ref = spawn(Behaviors.withMdc(Map("hasMdc" -> "true"))(behavior))
      EventFilter
        .custom(
          {
            case logEvent if logEvent.level == Logging.InfoLevel =>
              logEvent.message should ===("message")
              logEvent.mdc should ===(Map("hasMdc" -> true))
              true
            case other => system.log.error(s"Unexpected log event: {}", other); false
          },
          occurrences = 1)
        .intercept {
          ref ! "message"
        }

      ref ! "new-behavior"

      EventFilter
        .custom(
          {
            case logEvent if logEvent.level == Logging.InfoLevel =>
              logEvent.message should ===("message")
              logEvent.mdc should ===(Map("hasMdc" -> true)) // original mdc should stay
              true
            case other => system.log.error(s"Unexpected log event: {}", other); false
          },
          occurrences = 1)
        .intercept {
          ref ! "message"
        }

    }

    "replace when behavior changes to other behavior wrapped in withMdc" in {
      // when it changes while running, we expect the latest one to apply
      val id = new AtomicInteger(0)
      def behavior: Behavior[String] =
        Behaviors.withMdc(Map("mdc-version" -> id.incrementAndGet().toString)) {
          Behaviors.receive { (context, message) =>
            message match {
              case "new-mdc" =>
                behavior
              case other =>
                context.log.info(other)
                Behaviors.same
            }
          }
        }

      val ref = spawn(behavior)
      EventFilter
        .custom(
          {
            case logEvent if logEvent.level == Logging.InfoLevel =>
              logEvent.message should ===("message")
              logEvent.mdc should ===(Map("mdc-version" -> 1))
              true
            case other => system.log.error(s"Unexpected log event: {}", other); false
          },
          occurrences = 1)
        .intercept {
          ref ! "message"
        }
      ref ! "new-mdc"
      EventFilter
        .custom(
          {
            case logEvent if logEvent.level == Logging.InfoLevel =>
              logEvent.message should ===("message")
              logEvent.mdc should ===(Map("mdc-version" -> 2)) // mdc should have been replaced
              true
            case other => system.log.error(s"Unexpected log event: {}", other); false
          },
          occurrences = 1)
        .intercept {
          ref ! "message"
        }

    }

    "provide a withMdc decorator" in {
      val behavior = Behaviors.withMdc[Protocol](Map("mdc" -> "outer"))(Behaviors.setup { context =>
        Behaviors.receiveMessage { _ =>
          org.slf4j.MDC.put("mdc", "inner")
          context.log.info("Got message log.withMDC!")
          // after log.withMdc so we know it didn't change the outer mdc
          context.log.info("Got message behavior.withMdc!")
          Behaviors.same
        }
      })

      // mdc on message
      val ref = spawn(behavior)
      EventFilter
        .custom(
          {
            case logEvent if logEvent.level == Logging.InfoLevel =>
              logEvent.message should ===("Got message behavior.withMdc!")
              logEvent.mdc should ===(Map("mdc" -> "outer"))
              true
            case other => system.log.error(s"Unexpected log event: {}", other); false
          },
          occurrences = 1)
        .intercept {
          EventFilter
            .custom(
              {
                case logEvent if logEvent.level == Logging.InfoLevel =>
                  logEvent.message should ===("Got message log.withMDC!")
                  logEvent.mdc should ===(Map("mdc" -> "inner"))
                  true
                case other => system.log.error(s"Unexpected log event: {}", other); false
              },
              occurrences = 1)
            .intercept {
              ref ! Message(1, "first")
            }
        }
    }

  }

}
