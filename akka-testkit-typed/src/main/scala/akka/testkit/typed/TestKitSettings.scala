/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.testkit.typed

import com.typesafe.config.Config
import scala.concurrent.duration.FiniteDuration
import akka.util.Timeout
import akka.actor.typed.ActorSystem

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
  val SingleExpectDefaultTimeout: FiniteDuration = config.getMillisDuration("single-expect-default")
  val ExpectNoMessageDefaultTimeout: FiniteDuration = config.getMillisDuration("expect-no-message-default")
  val DefaultTimeout: Timeout = Timeout(config.getMillisDuration("default-timeout"))

  def dilated(duration: FiniteDuration): FiniteDuration = (duration * TestTimeFactor).asInstanceOf[FiniteDuration]
}
