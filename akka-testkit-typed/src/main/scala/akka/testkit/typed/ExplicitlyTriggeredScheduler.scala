package akka.testkit.typed

import java.util.concurrent.ThreadFactory

import akka.event.LoggingAdapter
import com.typesafe.config.Config

import scala.concurrent.duration.FiniteDuration

class ExplicitlyTriggeredScheduler(config: Config, log: LoggingAdapter, tf: ThreadFactory) extends akka.testkit.ExplicitlyTriggeredScheduler(config, log, tf) {
  // We can add convenience methods for Typed here as necessary.
}
