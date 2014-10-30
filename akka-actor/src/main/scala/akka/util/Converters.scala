package akka.util

import scala.concurrent.duration.{ Duration, FiniteDuration }
import language.implicitConversions

object Converters {
  implicit def convertToFiniteDuration(duration: Duration) = {
    new FiniteDuration(duration.length, duration.unit)
  }
}