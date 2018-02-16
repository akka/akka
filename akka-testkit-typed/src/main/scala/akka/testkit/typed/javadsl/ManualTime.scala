/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.testkit.typed.javadsl

import akka.actor.typed.ActorSystem
import com.typesafe.config.Config

import scala.annotation.varargs
import scala.concurrent.duration.{ Duration, FiniteDuration }

object ManualTime {

  /**
   * Config that needs to be in place for the actor system to use the manual
   */
  def config(): Config = akka.testkit.typed.scaladsl.ManualTime.config

  /**
   * Access the manual scheduler, note that you need to setup the actor system/testkit with [[config()]] for this to
   * work.
   */
  def get[A](system: ActorSystem[A]): ManualTime =
    system.scheduler match {
      case sc: akka.testkit.ExplicitlyTriggeredScheduler ⇒ new ManualTime(sc)
      case _ ⇒ throw new IllegalArgumentException("ActorSystem not configured with explicitly triggered scheduler, " +
        "make sure to include akka.testkit.typed.javadsl.ManualTime.config() when setting up the test")
    }

}

final class ManualTime(delegate: akka.testkit.ExplicitlyTriggeredScheduler) {

  /**
   * Advance the clock by the specified duration, executing all outstanding jobs on the calling thread before returning.
   *
   * We will not add a dilation factor to this amount, since the scheduler API also does not apply dilation.
   * If you want the amount of time passed to be dilated, apply the dilation before passing the delay to
   * this method.
   */
  def timePasses(amount: FiniteDuration): Unit = delegate.timePasses(amount)

  @varargs
  def expectNoMessageFor(duration: FiniteDuration, on: TestProbe[_]*): Unit = {
    delegate.timePasses(duration)
    on.foreach(_.expectNoMessage(Duration.Zero))
  }

}
