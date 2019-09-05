/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.scaladsl

import akka.actor.testkit.typed.scaladsl.LoggingEventFilter
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.testkit.typed.scaladsl.LogCapturing
import org.scalatest.WordSpecLike
import org.slf4j.LoggerFactory

object LoggerOpsSpec {
  case class Value1(i: Int)
  case class Value2(i: Int)
  case class Value3(i: Int)
}

class LoggerOpsSpec extends ScalaTestWithActorTestKit with WordSpecLike with LogCapturing {
  import LoggerOpsSpec._

  val log = LoggerFactory.getLogger(getClass)

  "LoggerOps" must {

    "provide extension method for 2 arguments" in {
      LoggingEventFilter.info("[template a b]").intercept {
        log.info2("[template {} {}]", "a", "b")
      }
      LoggingEventFilter.info("[template a 2]").intercept {
        log.info2("[template {} {}]", "a", 2)
      }
      LoggingEventFilter.info("[template 1 2]").intercept {
        log.info2("[template {} {}]", 1, 2)
      }
      LoggingEventFilter.info("[template 1 b]").intercept {
        log.info2("[template {} {}]", 1, "b")
      }
      LoggingEventFilter.info("[template a Value2(2)]").intercept {
        log.info2("[template {} {}]", "a", Value2(2))
      }
      LoggingEventFilter.info("[template Value1(1) Value1(1)]").intercept {
        log.info2("[template {} {}]", Value1(1), Value1(1))
      }
      LoggingEventFilter.info("[template Value1(1) Value2(2)]").intercept {
        log.info2("[template {} {}]", Value1(1), Value2(2))
      }
    }

    "provide extension method for vararg arguments" in {
      LoggingEventFilter.info("[template a b c]").intercept {
        log.infoN("[template {} {} {}]", "a", "b", "c")
      }
      LoggingEventFilter.info("[template a b 3]").intercept {
        log.infoN("[template {} {} {}]", "a", "b", 3)
      }
      LoggingEventFilter.info("[template a 2 c]").intercept {
        log.infoN("[template {} {} {}]", "a", 2, "c")
      }
      LoggingEventFilter.info("[template 1 2 3]").intercept {
        log.infoN("[template {} {} {}]", 1, 2, 3)
      }
      LoggingEventFilter.info("[template 1 b c]").intercept {
        log.infoN("[template {} {} {}]", 1, "b", "c")
      }
      LoggingEventFilter.info("[template a Value2(2) Value3(3)]").intercept {
        log.infoN("[template {} {} {}]", "a", Value2(2), Value3(3))
      }
      LoggingEventFilter.info("[template Value1(1) Value1(1) Value1(1)]").intercept {
        log.infoN("[template {} {} {}]", Value1(1), Value1(1), Value1(1))
      }
      LoggingEventFilter.info("[template Value1(1) Value2(2) Value3(3)]").intercept {
        log.infoN("[template {} {} {}]", Value1(1), Value2(2), Value3(3))
      }
    }
  }
}
