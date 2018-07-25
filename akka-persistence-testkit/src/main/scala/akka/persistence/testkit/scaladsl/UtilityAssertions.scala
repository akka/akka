/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.scaladsl

import scala.annotation.tailrec
import scala.util.control.NonFatal

trait UtilityAssertions {

  import scala.concurrent.duration._

  protected def now: FiniteDuration = System.nanoTime.nanos

  def awaitAssert[A](a: ⇒ A, max: FiniteDuration, interval: Duration = 100.millis): A = {
    val stop = now + max

    @tailrec
    def poll(t: Duration): A = {
      // cannot use null-ness of result as signal it failed
      // because Java API and not wanting to return a value will be "return null"
      var failed = false
      val result: A =
        try {
          val aRes = a
          failed = false
          aRes
        } catch {
          case NonFatal(e) ⇒
            failed = true
            if ((now + t) >= stop) throw e
            else null.asInstanceOf[A]
        }

      if (!failed) result
      else {
        Thread.sleep(t.toMillis)
        poll((stop - now) min interval)
      }
    }

    poll(max min interval)
  }

  def assertCondition(a: ⇒ Boolean, max: FiniteDuration, interval: Duration = 100.millis): Unit = {
    val stop = now + max

    @tailrec
    def poll(t: Duration): Unit = {
      // cannot use null-ness of result as signal it failed
      // because Java API and not wanting to return a value will be "return null"
      val result: Boolean = a
      val instantNow = now

      if (result && instantNow < stop) {
        Thread.sleep(t.toMillis)
        poll((stop - now) min interval)
      } else if (!result) {
        throw new AssertionError("Assert condition failed")
      }
    }

    poll(max min interval)
  }

}

object UtilityAssertions extends UtilityAssertions

