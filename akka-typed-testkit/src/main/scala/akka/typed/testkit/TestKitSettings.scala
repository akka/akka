/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.testkit

import com.typesafe.config.Config
import scala.concurrent.duration.FiniteDuration
import akka.util.Timeout
import akka.typed.ActorSystem

object TestKitSettings {
  /**
   * Reads configuration settings from `akka.typed.test` section.
   */
  def apply(system: ActorSystem[_]): TestKitSettings =
    apply(system.settings.config)

  /**
   * Reads configuration settings from given `Config` that
   * must have the same layout as the `akka.typed.test` section.
   */
  def apply(config: Config): TestKitSettings =
    new TestKitSettings(config)
}

class TestKitSettings(val config: Config) {

  import akka.util.Helpers._

  val TestTimeFactor = config.getDouble("akka.typed.test.timefactor").
    requiring(tf ⇒ !tf.isInfinite && tf > 0, "akka.typed.test.timefactor must be positive finite double")
  val SingleExpectDefaultTimeout: FiniteDuration = config.getMillisDuration("akka.typed.test.single-expect-default")
  val DefaultTimeout: Timeout = Timeout(config.getMillisDuration("akka.typed.test.default-timeout"))
}
