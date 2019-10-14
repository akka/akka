/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.scaladsl

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

import akka.actor.testkit.typed.TestException
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.testkit.typed.scaladsl.LoggingTestKit
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.typed.ActorTags
import akka.actor.typed.Behavior
import akka.actor.typed.internal.ActorMdc
import akka.actor.typed.scaladsl.adapter._
import akka.event.DefaultLoggingFilter
import akka.event.Logging.DefaultLogger
import akka.event.slf4j.Slf4jLogger
import akka.event.slf4j.Slf4jLoggingFilter
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpecLike
import org.slf4j.LoggerFactory
import org.slf4j.MDC
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
class BehaviorWhereTheLoggerIsUsed(context: ActorContext[String]) extends AbstractBehavior[String](context) {
  context.log.info("Starting up")
  override def onMessage(msg: String): Behavior[String] = {
    Behaviors.same
  }
}

class ActorLoggingSpec extends ScalaTestWithActorTestKit("""
    akka.loglevel = DEBUG # test verifies debug
    """) with WordSpecLike with LogCapturing {

  val marker = new BasicMarkerFactory().getMarker("marker")
  val cause = TestException("böö")

  implicit val classic = system.toClassic

  class AnotherLoggerClass

  "Logging in an actor" must {

    "be conveniently available from the context" in {

      val behavior: Behavior[String] = Behaviors.setup[String] { context =>
        context.log.info("Started")

        Behaviors.receive { (context, message) =>
          context.log.info("got message {}", message)
          Behaviors.same
        }
      }

      val actor = LoggingTestKit.info("Started").intercept(spawn(behavior, "the-actor"))

      LoggingTestKit.info("got message Hello").intercept(actor ! "Hello")

    }

    "log with custom Logger class" in {
      val behavior: Behavior[String] = Behaviors.setup[String] { context =>
        context.setLoggerName(classOf[AnotherLoggerClass])
        context.log.info("Started")

        Behaviors.receive { (context, message) =>
          context.log.info("got message {}", message)
          Behaviors.same
        }
      }

      val actor =
        LoggingTestKit.info("Started").withLoggerName(classOf[AnotherLoggerClass].getName).intercept {
          spawn(behavior, "the-other-actor")
        }

      // verify that it's logged with `AnotherLoggerClass`
      // verify that it's only capturing log events for that logger and not any other logger when interceptLogger
      // is used
      val count = new AtomicInteger
      LoggingTestKit
        .custom { logEvent =>
          count.incrementAndGet()
          logEvent.message == "got message Hello" && logEvent.loggerName == classOf[AnotherLoggerClass].getName
        }
        .withLoggerName(classOf[AnotherLoggerClass].getName)
        .withOccurrences(2)
        .intercept {
          actor ! "Hello"
          LoggerFactory.getLogger(classOf[ActorLoggingSpec]).debug("Hello from other logger")
          actor ! "Hello"
        }
      count.get should ===(2)

    }

    "contain the class name where the first log was called" in {
      val eventFilter = LoggingTestKit.custom({
        case event if event.loggerName == classOf[ActorLoggingSpec].getName =>
          true
        case event =>
          println(event.loggerName)
          false
      })

      eventFilter.intercept(spawn(Behaviors.setup[String] { context =>
        context.log.info("Started")

        Behaviors.receive { (context, message) =>
          context.log.info("got message {}", message)
          Behaviors.same
        }
      }, "the-actor-with-class"))

    }

    "contain the object class name where the first log was called" in {
      val eventFilter = LoggingTestKit.custom({
        case event if event.loggerName == WhereTheBehaviorIsDefined.getClass.getName => true
        case other =>
          println(other.loggerName)
          false
      })

      eventFilter.intercept(spawn(WhereTheBehaviorIsDefined.behavior, "the-actor-with-object"))
    }

    "contain the abstract behavior class name where the first log was called" in {
      val eventFilter = LoggingTestKit.custom({
        case event if event.loggerName == classOf[BehaviorWhereTheLoggerIsUsed].getName => true
        case other =>
          println(other.loggerName)
          false
      })

      eventFilter.intercept {
        spawn(Behaviors.setup[String](context => new BehaviorWhereTheLoggerIsUsed(context)), "the-actor-with-behavior")
      }
    }

    "pass markers to the log" in {
      LoggingTestKit
        .custom { event =>
          event.marker.map(_.getName) == Option(marker.getName)
        }
        .withOccurrences(5)
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
      LoggingTestKit
        .custom { event =>
          event.throwable == Option(cause)
        }
        .withOccurrences(2)
        .intercept(spawn(Behaviors.setup[Any] { context =>
          context.log.warn("whatever", cause)
          context.log.warn(marker, "whatever", cause)
          Behaviors.stopped
        }))
    }

    "provide a whole bunch of logging overloads" in {

      // Not the best test but at least it exercises every log overload ;)

      LoggingTestKit
        .custom { _ =>
          true // any is fine, we're just after the right count of statements reaching the listener
        }
        .withOccurrences(36)
        .intercept({
          spawn(Behaviors.setup[String] {
            context =>
              context.log.debug("message")
              context.log.debug("{}", "arg1")
              // using `: Any` to avoid "ambiguous reference to overloaded definition", see also LoggerOpsSpec
              context.log.debug("{} {}", "arg1", "arg2": Any)
              context.log.debug("{} {} {}", "arg1", "arg2", "arg3")
              context.log.debug(marker, "message")
              context.log.debug(marker, "{}", "arg1")
              context.log.debug(marker, "{} {}", "arg1", "arg2": Any)
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
              // using to avoid vararg problem for primitive type, see also LoggerOpsSpec
              context.log.error("{} {} {}", "arg1", "arg2", 3.asInstanceOf[AnyRef])
              context.log.error(marker, "message")
              context.log.error(marker, "{}", "arg1")
              context.log.error(marker, "{} {}", "arg1", "arg2": Any)
              context.log.error(marker, "{} {} {}", "arg1", "arg2", "arg3")
              context.log.error(marker, "{} {} {}", "arg1", "arg2", 3.asInstanceOf[AnyRef])
              context.log.error("message", cause)

              Behaviors.stopped
          })
        })
    }

    "use Slf4jLogger from akka-slf4j automatically" in {
      LoggingTestKit.info("via Slf4jLogger").intercept {
        // this will log via classic eventStream
        system.toClassic.log.info("via Slf4jLogger")
      }
    }

    "pass tags from props to MDC" in {
      val behavior = Behaviors.setup[String] { ctx =>
        ctx.log.info("Starting up")

        Behaviors.receiveMessage {
          case msg =>
            ctx.log.info("Got message {}", msg)
            Behaviors.same
        }
      }
      val actor =
        LoggingTestKit.info("Starting up").withMdc(Map(ActorMdc.TagsKey -> "tag1,tag2")).intercept {
          spawn(behavior, ActorTags("tag1", "tag2"))
        }

      LoggingTestKit.info("Got message").withMdc(Map(ActorMdc.TagsKey -> "tag1,tag2")).intercept {
        actor ! "ping"
      }
    }

  }

  "SLF4J Settings" must {
    import akka.actor.typed.scaladsl.adapter._
    import akka.actor.ExtendedActorSystem
    import akka.actor.{ ActorSystem => ClassicActorSystem }

    "by default be amended to use Slf4jLogger" in {
      system.settings.config.getStringList("akka.loggers").size() should ===(1)
      system.settings.config.getStringList("akka.loggers").get(0) should ===(classOf[Slf4jLogger].getName)
      system.settings.config.getString("akka.logging-filter") should ===(classOf[Slf4jLoggingFilter].getName)

      system.toClassic.settings.Loggers should ===(List(classOf[Slf4jLogger].getName))
      system.toClassic.settings.LoggingFilter should ===(classOf[Slf4jLoggingFilter].getName)
    }

    "by default be amended to use Slf4jLogger when starting classic ActorSystem" in {
      val classicSys = akka.actor.ActorSystem(system.name)
      try {
        classicSys.settings.config.getStringList("akka.loggers").size() should ===(1)
        classicSys.settings.config.getStringList("akka.loggers").get(0) should ===(classOf[Slf4jLogger].getName)
        classicSys.settings.config.getString("akka.logging-filter") should ===(classOf[Slf4jLoggingFilter].getName)

        classicSys.settings.Loggers should ===(List(classOf[Slf4jLogger].getName))
        classicSys.settings.LoggingFilter should ===(classOf[Slf4jLoggingFilter].getName)

      } finally {
        ActorTestKit.shutdown(classicSys.toTyped)
      }
    }

    "not be amended when use-slf4j=off" in {
      val dynamicAccess = system.toClassic.asInstanceOf[ExtendedActorSystem].dynamicAccess
      val config = ClassicActorSystem.Settings.amendSlf4jConfig(
        ConfigFactory.parseString("akka.use-slf4j = off").withFallback(ConfigFactory.defaultReference()),
        dynamicAccess)
      config.getStringList("akka.loggers").size() should ===(1)
      config.getStringList("akka.loggers").get(0) should ===(classOf[DefaultLogger].getName)
      config.getString("akka.logging-filter") should ===(classOf[DefaultLoggingFilter].getName)
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
      val ref = LoggingTestKit
        .info("Starting")
        // not counting for example "akkaSource", but it shouldn't have any other entries
        .withCustom(logEvent => logEvent.mdc.keysIterator.forall(_.startsWith("akka")))
        .intercept {
          spawn(behaviors)
        }

      // mdc on message
      LoggingTestKit.info("Got message!").withMdc(Map("static" -> "1", "txId" -> "1", "first" -> "true")).intercept {
        ref ! Message(1, "first")
      }

      // mdc does not leak between messages
      LoggingTestKit
        .info("Got message!")
        .withMdc(Map("static" -> "1", "txId" -> "2"))
        .withCustom(event => !event.mdc.contains("first"))
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
      LoggingTestKit
        .info("message")
        .withMdc(Map("outermost" -> "true"))
        .withCustom(event => !event.mdc.contains("innermost"))
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
      LoggingTestKit.info("message").withMdc(Map("hasMdc" -> "true")).intercept {
        ref ! "message"
      }

      ref ! "new-behavior"

      LoggingTestKit
        .info("message")
        .withMdc(Map("hasMdc" -> "true")) // original mdc should stay
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
      LoggingTestKit.info("message").withMdc(Map("mdc-version" -> "1")).intercept {
        ref ! "message"
      }
      ref ! "new-mdc"
      LoggingTestKit
        .info("message")
        .withMdc(Map("mdc-version" -> "2")) // mdc should have been replaced
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
      LoggingTestKit.info("first").withMdc(Map("mdc" -> "outer")).intercept {
        LoggingTestKit.info("second").withMdc(Map("mdc" -> "inner-outer")).intercept {
          ref ! Message(1, "first")
        }
      }
    }

    "always include some MDC values in the log" in {
      // need AtomicReference because LoggingFilter defined before actor is created and ActorTestKit names are dynamic
      val actorPathStr = new AtomicReference[String]
      val behavior =
        Behaviors.setup[Message] { context =>
          actorPathStr.set(context.self.path.toString)
          context.log.info("Starting")
          Behaviors.receiveMessage { _ =>
            if (MDC.get("logSource") != null)
              throw new IllegalStateException("MDC wasn't cleared. logSource has value before context.log is accessed.")
            context.log.info("Got message!")
            Behaviors.same
          }
        }

      // log from setup
      // can't use LoggingEventFilter.withMdc here because the actorPathStr isn't know yet
      val ref =
        LoggingTestKit.info("Starting").withCustom(event => event.mdc("akkaSource") == actorPathStr.get).intercept {
          spawn(behavior)
        }

      // on message
      LoggingTestKit.info("Got message!").withMdc(Map("akkaSource" -> actorPathStr.get)).withOccurrences(10).intercept {
        (1 to 10).foreach { n =>
          ref ! Message(n, s"msg-$n")
        }
      }

    }

  }

}
