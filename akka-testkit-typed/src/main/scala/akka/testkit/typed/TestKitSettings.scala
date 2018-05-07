/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit.typed

import com.typesafe.config.Config

import scala.concurrent.duration.FiniteDuration
import akka.util.Timeout
import akka.actor.typed.ActorSystem

import scala.util.control.NoStackTrace

/**
 * Exception without stack trace to use for verifying exceptions in tests
 */
final case class TE(message: String) extends RuntimeException(message) with NoStackTrace

object TestKitSettings {
  /**
   * Reads configuration settings from `akka.actor.typed.test` section.
   */
  def apply(system: ActorSystem[_]): TestKitSettings =
    apply(system.settings.config.getConfig("akka.actor.typed.test"))

  /**
   * Reads configuration settings from given `Config` that
   * must have the same layout as the `akka.actor.typed.test` section.
   */
  def apply(config: Config): TestKitSettings =
    new TestKitSettings(config)

  /**
   * Java API: Reads configuration settings from `akka.actor.typed.test` section.
   */
  def create(system: ActorSystem[_]): TestKitSettings =
    apply(system)

  /**
   * Reads configuration settings from given `Config` that
   * must have the same layout as the `akka.actor.typed.test` section.
   */
  def create(config: Config): TestKitSettings =
    new TestKitSettings(config)
}

final class TestKitSettings(val config: Config) {

  import akka.util.Helpers._

  val TestTimeFactor = config.getDouble("timefactor").
    requiring(tf ⇒ !tf.isInfinite && tf > 0, "timefactor must be positive finite double")

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
    (duration * TestTimeFactor).asInstanceOf[FiniteDuration]

  /**
   * Java API: Scale the `duration` with the configured `TestTimeFactor`
   */
  def dilated(duration: java.time.Duration): java.time.Duration =
    java.time.Duration.ofMillis((duration.toMillis * TestTimeFactor).toLong)
}
