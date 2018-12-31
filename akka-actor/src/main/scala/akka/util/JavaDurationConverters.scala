/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util
import java.time.{ Duration ⇒ JDuration }

import scala.concurrent.duration.{ Duration, FiniteDuration }
/**
 * INTERNAL API
 */
private[akka] object JavaDurationConverters {
  def asFiniteDuration(duration: JDuration): FiniteDuration = duration.asScala

  final implicit class JavaDurationOps(val self: JDuration) extends AnyVal {
    def asScala: FiniteDuration = Duration.fromNanos(self.toNanos)
  }

  final implicit class ScalaDurationOps(val self: Duration) extends AnyVal {
    def asJava: JDuration = JDuration.ofNanos(self.toNanos)
  }
}
