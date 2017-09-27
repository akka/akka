/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.testkit

import scala.concurrent.duration.{ Duration, FiniteDuration }
import scala.reflect.ClassTag
import scala.collection.immutable
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.typed.ActorSystem

package object scaladsl {

  /**
   * Scala API. Scale timeouts (durations) during tests with the configured
   * 'akka.test.timefactor'.
   * Implicit class providing `dilated` method.
   *
   * {{{
   * import scala.concurrent.duration._
   * import akka.typed.testkit.scaladsl._
   * 10.milliseconds.dilated
   * }}}
   *
   * Uses the scaling factor from the `TestTimeFactor` in the [[TestKitSettings]]
   * (in implicit scope).
   *
   */
  implicit class TestDuration(val duration: FiniteDuration) extends AnyVal {
    def dilated(implicit settings: TestKitSettings): FiniteDuration =
      (duration * settings.TestTimeFactor).asInstanceOf[FiniteDuration]
  }

}
