/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util
import java.time.{ Duration => JDuration }

import scala.concurrent.duration.Duration

import akka.annotation.InternalStableApi

/**
 * INTERNAL API
 */
@InternalStableApi
private[akka] object JavaDurationConverters {
  final implicit class ScalaDurationOps(val self: Duration) extends AnyVal {
    def asJava: JDuration = JDuration.ofNanos(self.toNanos)
  }
}
