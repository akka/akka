/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.testkit.typed.javadsl

import java.util.concurrent.ThreadFactory

import akka.event.LoggingAdapter
import com.typesafe.config.Config

import scala.annotation.varargs
import scala.concurrent.duration.{ Duration, FiniteDuration }

class ExplicitlyTriggeredScheduler(config: Config, log: LoggingAdapter, tf: ThreadFactory) extends akka.testkit.ExplicitlyTriggeredScheduler(config, log, tf) {

  @varargs
  def expectNoMessageFor(duration: FiniteDuration, on: TestProbe[_]*): Unit = {
    timePasses(duration)
    on.foreach(_.expectNoMessage(Duration.Zero))
  }

}
