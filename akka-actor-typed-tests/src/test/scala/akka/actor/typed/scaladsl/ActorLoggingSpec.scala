/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor.typed.scaladsl

import akka.actor.typed.{ TypedAkkaSpec, scaladsl }
import akka.testkit.EventFilter
import akka.testkit.typed.TestKit
import com.typesafe.config.ConfigFactory
import akka.actor.typed.scaladsl.adapter._
import akka.event.Logging

class ActorLoggingSpec extends TestKit(ConfigFactory.parseString(
  """akka.loggers = ["akka.testkit.TestEventListener"]""")) with TypedAkkaSpec {

  implicit val untyped = system.toUntyped

  // FIXME test coverage of all those gazilion overloads of log methods

  "Logging in a typed actor" must {

    "be conveniently available from the ctx" in {
      val actor = EventFilter.info("Started", source = "akka://ActorLoggingSpec/user/the-actor", occurrences = 1).intercept {
        spawn(Behaviors.deferred[String] { ctx ⇒
          ctx.log.info("Started")

          Behaviors.immutable { (ctx, msg) ⇒
            ctx.log.info("got message {}", msg)
            Behaviors.same
          }
        }, "the-actor")
      }

      EventFilter.info("got message Hello", source = "akka://ActorLoggingSpec/user/the-actor", occurrences = 1).intercept {
        actor ! "Hello"
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
        { (msg) ⇒
          if (msg.transactionId == 1)
            Map(
              "txId" -> msg.transactionId,
              "first" -> true
            )
          else Map("txId" -> msg.transactionId)
        },
        Behaviors.deferred { ctx ⇒
          ctx.log.info("Starting")
          Behaviors.immutable { (ctx, msg) ⇒
            ctx.log.info("Got message!")
            Behaviors.same
          }
        }
      )

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
          logEvent.mdc should ===(Map("txId" -> 1L, "first" -> true))
          true
        case other ⇒ system.log.error(s"Unexpected log event: {}", other); false
      }, occurrences = 1).intercept {
        ref ! Message(1, "first")
      }

      // mdc does not leak between messages
      EventFilter.custom({
        case logEvent if logEvent.level == Logging.InfoLevel ⇒
          logEvent.message should ===("Got message!")
          logEvent.mdc should ===(Map("txId" -> 2L))
          true
        case other ⇒ system.log.error(s"Unexpected log event: {}", other); false
      }, occurrences = 1).intercept {
        ref ! Message(2, "second")
      }
    }

  }

}
