/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.scaladsl

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.testkit.typed.TestException
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.testkit.LoggingEventFilter._
import akka.actor.typed.testkit.{ AppenderInterceptor, LoggingEventFilter }
import akka.actor.typed.{ ActorRef, Behavior, LogOptions }
import akka.event.Logging
import akka.event.Logging.{ LogEvent, LogEventWithCause, LogEventWithMarker }
import akka.testkit.EventFilter
import ch.qos.logback.classic.spi.ILoggingEvent
import org.scalatest.WordSpecLike
import org.slf4j.event.{ Level, LoggingEvent }
import org.slf4j.helpers.{ BasicMarkerFactory, SubstituteLogger, SubstituteLoggerFactory }

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
    akka.loggers = ["akka.actor.typed.testkit.TestEventListener"]
    """) with WordSpecLike {

  val marker = new BasicMarkerFactory().getMarker("marker")
  val cause = new TestException("böö")

  implicit val untyped = system.toUntyped

  "Logging in a typed actor" must {

    "log messages and signals" in {

      val factory = new SubstituteLoggerFactory()
      val substituteLogger: SubstituteLogger = factory.getLogger("substitute").asInstanceOf[SubstituteLogger]
      val opts = LogOptions().withLevel(Level.DEBUG).withLogger(substituteLogger)

      val behavior: Behavior[String] = Behaviors.logMessages(opts, Behaviors.ignore)
      val ref: ActorRef[String] = spawn(behavior)

      debug(s"actor ${ref.path.toString} received message Hello", source = ref.path.toString, occurrences = 1)
        .intercept(ref ! "Hello", factory.getEventQueue)

      debug(s"actor ${ref.path.toString} received signal PostStop", source = ref.path.toString, occurrences = 1)
        .intercept(testKit.stop(ref), factory.getEventQueue)
    }

    //TODO be aware of context.log.xyz logging are very different as they aren't created by the InterceptorImpl! @see LogMessagesInterceptor.aroundReceive
    //  That's why AppenderInterceptor.events approach has been taken
    "be conveniently available from the context" in {

      val behavior: Behavior[String] = Behaviors.setup[String] { context =>
        println(s"context out ${context.executionContext.hashCode()}")
        context.log.info("Started")

        Behaviors.receive { (context, message) =>
          println(s"context in ${context.executionContext.hashCode()}")

          context.log.info("got message {}", message)
          Behaviors.same
        }
      }

      val actor = LoggingEventFilter
        .info("Started", occurrences = 1)
        .interceptIt(spawn(behavior, "the-actor"), AppenderInterceptor.events)

      LoggingEventFilter
        .info("got message Hello", occurrences = 1)
        .interceptIt(actor ! "Hello", AppenderInterceptor.events)

    }

    "contain the class name where the first log was called" in {
      val eventFilter = custom({
        case l: LoggingEvent if l.getLoggerName == classOf[ActorLoggingSpec].getName =>
          true
        case l: LoggingEvent =>
          println(l.getLoggerName)
          false
      }, occurrences = 1)

        eventFilter.interceptIt(spawn(Behaviors.setup[String] { context =>
          context.log.info("Started")

          Behaviors.receive { (context, message) =>
            context.log.info("got message {}", message)
            Behaviors.same
          }
        }, "the-actor-with-class"), AppenderInterceptor.events)

    }
    //TODO all below

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
      //TODO WIP...
      val eventFilter = custom({
        case l: ILoggingEvent =>
          l.getLoggerName == classOf[SomeClass] &&
          l.getCallerData == "who-knows-where-it-came-from" &&
          l.getMDCPropertyMap == Map("mdc" -> true) // mdc should be kept
      }, occurrences = 1)

      spawn(Behaviors.setup[String] { context =>
        context.log.info("Started")
        Behaviors.empty
      }, "the-actor-with-custom-class")
      Thread.sleep(1)
      eventFilter.interceptIt(println(""), AppenderInterceptor.events)

    }

    "pass markers to the log" in {
      EventFilter
        .custom({
          case event: LogEventWithMarker if event.marker.name == marker.getName => true
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
