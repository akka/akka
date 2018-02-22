/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit.typed

import java.util.concurrent.ThreadFactory

import com.typesafe.config.Config

import scala.annotation.varargs
import scala.concurrent.duration.{ Duration, FiniteDuration }

import akka.event.LoggingAdapter

class ExplicitlyTriggeredScheduler(config: Config, log: LoggingAdapter, tf: ThreadFactory) extends akka.testkit.ExplicitlyTriggeredScheduler(config, log, tf) {

  @varargs
  def expectNoMessageFor(duration: FiniteDuration, on: scaladsl.TestProbe[_]*): Unit = {
    timePasses(duration)
    on.foreach(_.expectNoMessage(Duration.Zero))
  }
}
