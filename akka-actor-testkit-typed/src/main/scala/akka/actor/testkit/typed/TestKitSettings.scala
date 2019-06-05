/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed

import com.typesafe.config.Config

import scala.concurrent.duration.{ Duration, FiniteDuration }
import akka.util.JavaDurationConverters._
import akka.util.Timeout
import akka.actor.typed.ActorSystem

object TestKitSettings {

  /**
   * Reads configuration settings from `akka.actor.testkit.typed` section.
   */
  def apply(system: ActorSystem[_]): TestKitSettings =
    apply(system.settings.config.getConfig("akka.actor.testkit.typed"))

  /**
   * Reads configuration settings from given `Config` that
   * must have the same layout as the `akka.actor.testkit.typed` section.
   */
  def apply(config: Config): TestKitSettings =
    new TestKitSettings(config)

  /**
   * Java API: Reads configuration settings from `akka.actor.testkit.typed` section.
   */
  def create(system: ActorSystem[_]): TestKitSettings =
    apply(system)

  /**
   * Reads configuration settings from given `Config` that
   * must have the same layout as the `akka.actor.testkit.typed` section.
   */
  def create(config: Config): TestKitSettings =
    new TestKitSettings(config)
}

final class TestKitSettings(val config: Config) {

  import akka.util.Helpers._

  val TestTimeFactor = config
    .getDouble("timefactor")
    .requiring(tf => !tf.isInfinite && tf > 0, "timefactor must be positive finite double")

  /** dilated with `TestTimeFactor` */
  val SingleExpectDefaultTimeout: FiniteDuration = dilated(config.getMillisDuration("single-expect-default"))

  /** dilated with `TestTimeFactor` */
  val ExpectNoMessageDefaultTimeout: FiniteDuration = dilated(config.getMillisDuration("expect-no-message-default"))

  /** dilated with `TestTimeFactor` */
  val DefaultTimeout: Timeout = Timeout(dilated(config.getMillisDuration("default-timeout")))

  /** dilated with `TestTimeFactor` */
  val DefaultActorSystemShutdownTimeout: FiniteDuration = dilated(config.getMillisDuration("system-shutdown-default"))

  val ThrowOnShutdownTimeout: Boolean = config.getBoolean("throw-on-shutdown-timeout")

  /**
   * Scala API: Scale the `duration` with the configured `TestTimeFactor`
   */
  def dilated(duration: FiniteDuration): FiniteDuration =
    Duration.fromNanos((duration.toNanos * TestTimeFactor + 0.5).toLong)

  /**
   * Java API: Scale the `duration` with the configured `TestTimeFactor`
   */
  def dilated(duration: java.time.Duration): java.time.Duration =
    dilated(duration.asScala).asJava
}
