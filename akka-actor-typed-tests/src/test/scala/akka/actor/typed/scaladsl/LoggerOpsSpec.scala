/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.scaladsl

import akka.actor.testkit.typed.scaladsl.LoggingEventFilter
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.WordSpecLike
import org.slf4j.LoggerFactory

object LoggerOpsSpec {
  case class Value1(i: Int)
  case class Value2(i: Int)
  case class Value3(i: Int)
}

class LoggerOpsSpec extends ScalaTestWithActorTestKit with WordSpecLike {
  import LoggerOpsSpec._

  val log = LoggerFactory.getLogger(getClass)

  "LoggerOps" must {

    "provide extension method for 2 arguments" in {
      LoggingEventFilter.info(message = "[template a b]", occurrences = 1).intercept {
        log.info2("[template {} {}]", "a", "b")
      }
      LoggingEventFilter.info(message = "[template a 2]", occurrences = 1).intercept {
        log.info2("[template {} {}]", "a", 2)
      }
      LoggingEventFilter.info(message = "[template 1 2]", occurrences = 1).intercept {
        log.info2("[template {} {}]", 1, 2)
      }
      LoggingEventFilter.info(message = "[template 1 b]", occurrences = 1).intercept {
        log.info2("[template {} {}]", 1, "b")
      }
      LoggingEventFilter.info(message = "[template a Value2(2)]", occurrences = 1).intercept {
        log.info2("[template {} {}]", "a", Value2(2))
      }
      LoggingEventFilter.info(message = "[template Value1(1) Value1(1)]", occurrences = 1).intercept {
        log.info2("[template {} {}]", Value1(1), Value1(1))
      }
      LoggingEventFilter.info(message = "[template Value1(1) Value2(2)]", occurrences = 1).intercept {
        log.info2("[template {} {}]", Value1(1), Value2(2))
      }
    }

    "provide extension method for vararg arguments" in {
      LoggingEventFilter.info(message = "[template a b c]", occurrences = 1).intercept {
        log.infoN("[template {} {} {}]", "a", "b", "c")
      }
      LoggingEventFilter.info(message = "[template a b 3]", occurrences = 1).intercept {
        log.infoN("[template {} {} {}]", "a", "b", 3)
      }
      LoggingEventFilter.info(message = "[template a 2 c]", occurrences = 1).intercept {
        log.infoN("[template {} {} {}]", "a", 2, "c")
      }
      LoggingEventFilter.info(message = "[template 1 2 3]", occurrences = 1).intercept {
        log.infoN("[template {} {} {}]", 1, 2, 3)
      }
      LoggingEventFilter.info(message = "[template 1 b c]", occurrences = 1).intercept {
        log.infoN("[template {} {} {}]", 1, "b", "c")
      }
      LoggingEventFilter.info(message = "[template a Value2(2) Value3(3)]", occurrences = 1).intercept {
        log.infoN("[template {} {} {}]", "a", Value2(2), Value3(3))
      }
      LoggingEventFilter.info(message = "[template Value1(1) Value1(1) Value1(1)]", occurrences = 1).intercept {
        log.infoN("[template {} {} {}]", Value1(1), Value1(1), Value1(1))
      }
      LoggingEventFilter.info(message = "[template Value1(1) Value2(2) Value3(3)]", occurrences = 1).intercept {
        log.infoN("[template {} {} {}]", Value1(1), Value2(2), Value3(3))
      }
    }
  }
}
