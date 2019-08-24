/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.scaladsl

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.testkit.typed.TestException
import akka.actor.testkit.typed.scaladsl.LoggingEventFilter
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.adapter._
import org.scalatest.WordSpecLike
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
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
    """) with WordSpecLike {

  val marker = new BasicMarkerFactory().getMarker("marker")
  val cause = TestException("böö")

  implicit val untyped = system.toUntyped

  class AnotherLoggerClass

  "Logging in a typed actor" must {

    "be conveniently available from the context" in {

      val behavior: Behavior[String] = Behaviors.setup[String] { context =>
        context.log.info("Started")

        Behaviors.receive { (context, message) =>
          context.log.info("got message {}", message)
          Behaviors.same
        }
      }

      val actor = LoggingEventFilter.info("Started", occurrences = 1).intercept(spawn(behavior, "the-actor"))

      LoggingEventFilter.info("got message Hello", occurrences = 1).intercept(actor ! "Hello")

    }

    "log with custom Logger class" in {
      val behavior: Behavior[String] = Behaviors.setup[String] { context =>
        context.setLoggerClass(classOf[AnotherLoggerClass])
        context.log.info("Started")

        Behaviors.receive { (context, message) =>
          context.log.info("got message {}", message)
          Behaviors.same
        }
      }

      val actor =
        LoggingEventFilter.info("Started", occurrences = 1).interceptLogger(classOf[AnotherLoggerClass].getName) {
          spawn(behavior, "the-other-actor")
        }

      // verify that it's logged with `AnotherLoggerClass`
      // verify that it's only capturing log events for that logger and not any other logger when interceptLogger
      // is used
      val count = new AtomicInteger
      LoggingEventFilter
        .custom({
          case logEvent =>
            count.incrementAndGet()
            logEvent.message == "got message Hello" && logEvent.loggerName == classOf[AnotherLoggerClass].getName
        }, occurrences = 2)
        .interceptLogger(classOf[AnotherLoggerClass].getName) {
          actor ! "Hello"
          LoggerFactory.getLogger(classOf[ActorLoggingSpec]).debug("Hello from other logger")
          actor ! "Hello"
        }
      count.get should ===(2)

    }

    "contain the class name where the first log was called" in {
      val eventFilter = LoggingEventFilter.custom({
        case event if event.loggerName == classOf[ActorLoggingSpec].getName =>
          true
        case event =>
          println(event.loggerName)
          false
      }, occurrences = 1)

      eventFilter.intercept(spawn(Behaviors.setup[String] { context =>
        context.log.info("Started")

        Behaviors.receive { (context, message) =>
          context.log.info("got message {}", message)
          Behaviors.same
        }
      }, "the-actor-with-class"))

    }

    "contain the object class name where the first log was called" in {
      val eventFilter = LoggingEventFilter.custom({
        case event if event.loggerName == WhereTheBehaviorIsDefined.getClass.getName => true
        case other =>
          println(other.loggerName)
          false
      }, occurrences = 1)

      eventFilter.intercept(spawn(WhereTheBehaviorIsDefined.behavior, "the-actor-with-object"))
    }

    "contain the abstract behavior class name where the first log was called" in {
      val eventFilter = LoggingEventFilter.custom({
        case event if event.loggerName == classOf[BehaviorWhereTheLoggerIsUsed].getName => true
        case other =>
          println(other.loggerName)
          false
      }, occurrences = 1)

      eventFilter.intercept {
        spawn(Behaviors.setup[String](context => new BehaviorWhereTheLoggerIsUsed(context)), "the-actor-with-behavior")
      }
    }

    "allow for adapting log source and class" in {
      // FIXME #26537
      pending
//      val eventFilter = custom({
//        case l: ILoggingEvent =>
//          l.getLoggerName == classOf[SomeClass].getName &&
//          l.getCallerData == "who-knows-where-it-came-from" &&
//          l.getMDCPropertyMap.containsKey("mdc") &&
//          l.getMDCPropertyMap.get("mdc") == "true" // mdc should be kept
//      }, occurrences = 1)
//
//      spawn(Behaviors.setup[String] { context =>
//        context.log.info("Started")
//        Behaviors.empty
//      }, "the-actor-with-custom-class")
//      Thread.sleep(1)
//      eventFilter.interceptIt(println(""), AppenderInterceptor.events)

    }

    "pass markers to the log" in {
      LoggingEventFilter
        .custom({
          case event if event.marker.map(_.getName) == Option(marker.getName) => true
        }, occurrences = 5)
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
      LoggingEventFilter
        .custom({
          case event if event.throwable == Option(cause) => true
        }, occurrences = 2)
        .intercept(spawn(Behaviors.setup[Any] { context =>
          context.log.warn("whatever", cause)
          context.log.warn(marker, "whatever", cause)
          Behaviors.stopped
        }))
    }

    "provide a whole bunch of logging overloads" in {

      // Not the best test but at least it exercises every log overload ;)

      LoggingEventFilter
        .custom({
          case _ => true // any is fine, we're just after the right count of statements reaching the listener
        }, occurrences = 34)
        .intercept({
          spawn(Behaviors.setup[String] {
            context =>
              context.log.debug("message")
              context.log.debug("{}", "arg1")
              context.log
                .debug("{} {}", "arg1", "arg2": Any) //using Int to avoid ambiguous reference to overloaded definition
              context.log.debug("{} {} {}", "arg1", "arg2", "arg3")
              context.log.debug(marker, "message")
              context.log.debug(marker, "{}", "arg1")
              context.log.debug(marker, "{} {}", "arg1", "arg2": Any) //using Int to avoid ambiguous reference to overloaded definition
              context.log.debug(marker, "{} {} {}", "arg1", "arg2", "arg3")

              context.log.info("message")
              context.log.info("{}", "arg1")
              context.log.info("{} {}", "arg1", "arg2": Any)
              context.log.info("{} {} {}", "arg1", "arg2", "arg3")
              context.log.info(marker, "message")
              context.log.info(marker, "{}", "arg1")
              context.log.info(marker, "{} {}", "arg1", "arg2": Any)
              context.log.info(marker, "{} {} {}", "arg1", "arg2", "arg3")

              context.log.warn("message")
              context.log.warn("{}", "arg1")
              context.log.warn("{} {}", "arg1", "arg2": Any)
              context.log.warn("{} {} {}", "arg1", "arg2", "arg3")
              context.log.warn(marker, "message")
              context.log.warn(marker, "{}", "arg1")
              context.log.warn(marker, "{} {}", "arg1", "arg2": Any)
              context.log.warn(marker, "{} {} {}", "arg1", "arg2", "arg3")
              context.log.warn("message", cause)

              context.log.error("message")
              context.log.error("{}", "arg1")
              context.log.error("{} {}", "arg1", "arg2": Any)
              context.log.error("{} {} {}", "arg1", "arg2", "arg3")
              context.log.error(marker, "message")
              context.log.error(marker, "{}", "arg1")
              context.log.error(marker, "{} {}", "arg1", "arg2": Any)
              context.log.error(marker, "{} {} {}", "arg1", "arg2", "arg3")
              context.log.error("message", cause)

              Behaviors.stopped
          })
        })
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

      // mdc on defer is empty
      val ref = LoggingEventFilter
        .custom(
          {
            case logEvent if logEvent.level == Level.INFO =>
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
      LoggingEventFilter
        .custom(
          {
            case logEvent if logEvent.level == Level.INFO =>
              logEvent.message should ===("Got message!")
              logEvent.mdc should ===(Map("static" -> "1", "txId" -> "1", "first" -> "true"))
              true
            case other => system.log.error(s"Unexpected log event: {}", other); false
          },
          occurrences = 1)
        .intercept {
          ref ! Message(1, "first")
        }

      // mdc does not leak between messages
      LoggingEventFilter
        .custom(
          {
            case logEvent if logEvent.level == Level.INFO =>
              logEvent.message should ===("Got message!")
              logEvent.mdc should ===(Map("static" -> "1", "txId" -> "2"))
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
      LoggingEventFilter
        .custom(
          {
            case logEvent if logEvent.level == Level.INFO =>
              logEvent.message should ===("message")
              logEvent.mdc should ===(Map("outermost" -> "true"))
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
      LoggingEventFilter
        .custom(
          {
            case logEvent if logEvent.level == Level.INFO =>
              logEvent.message should ===("message")
              logEvent.mdc should ===(Map("hasMdc" -> "true"))
              true
            case other => system.log.error(s"Unexpected log event: {}", other); false
          },
          occurrences = 1)
        .intercept {
          ref ! "message"
        }

      ref ! "new-behavior"

      LoggingEventFilter
        .custom(
          {
            case logEvent if logEvent.level == Level.INFO =>
              logEvent.message should ===("message")
              logEvent.mdc should ===(Map("hasMdc" -> "true")) // original mdc should stay
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
      LoggingEventFilter
        .custom(
          {
            case logEvent if logEvent.level == Level.INFO =>
              logEvent.message should ===("message")
              logEvent.mdc should ===(Map("mdc-version" -> "1"))
              true
            case other => system.log.error(s"Unexpected log event: {}", other); false
          },
          occurrences = 1)
        .intercept {
          ref ! "message"
        }
      ref ! "new-mdc"
      LoggingEventFilter
        .custom(
          {
            case logEvent if logEvent.level == Level.INFO =>
              logEvent.message should ===("message")
              logEvent.mdc should ===(Map("mdc-version" -> "2")) // mdc should have been replaced
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
          context.log.info("first")
          org.slf4j.MDC.put("mdc", "inner-" + org.slf4j.MDC.get("mdc"))
          context.log.info("second")
          Behaviors.same
        }
      })

      // mdc on message
      val ref = spawn(behavior)
      LoggingEventFilter
        .custom(
          {
            case logEvent if logEvent.level == Level.INFO =>
              logEvent.message should ===("first")
              logEvent.mdc should ===(Map("mdc" -> "outer"))
              true
            case other => system.log.error(s"Unexpected log event: {}", other); false
          },
          occurrences = 1)
        .intercept {
          LoggingEventFilter
            .custom(
              {
                case logEvent if logEvent.level == Level.INFO =>
                  logEvent.message should ===("second")
                  logEvent.mdc should ===(Map("mdc" -> "inner-outer"))
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
