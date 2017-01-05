/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.util
import java.time.{ Duration ⇒ JDuration }
import scala.concurrent.duration.Duration
/**
 * INTERNAL API
 */
private[akka] object JavaDurationConverters {
  final implicit class JavaDurationOps(val self: JDuration) extends AnyVal {
    def asScala: Duration = Duration.fromNanos(self.toNanos)
  }

  final implicit class ScalaDurationOps(val self: Duration) extends AnyVal {
    def asJava: JDuration = JDuration.ofNanos(self.toNanos)
  }
}
