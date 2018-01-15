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
    apply(system.settings.config)

  /**
   * Reads configuration settings from given `Config` that
   * must have the same layout as the `akka.actor.typed.test` section.
   */
  def apply(config: Config): TestKitSettings =
    new TestKitSettings(config)
}

class TestKitSettings(val config: Config) {

  import akka.util.Helpers._

  val TestTimeFactor = config.getDouble("akka.actor.typed.test.timefactor").
    requiring(tf ⇒ !tf.isInfinite && tf > 0, "akka.actor.typed.test.timefactor must be positive finite double")
  val SingleExpectDefaultTimeout: FiniteDuration = config.getMillisDuration("akka.actor.typed.test.single-expect-default")
  val ExpectNoMessageDefaultTimeout: FiniteDuration = config.getMillisDuration("akka.actor.typed.test.expect-no-message-default")
  val DefaultTimeout: Timeout = Timeout(config.getMillisDuration("akka.actor.typed.test.default-timeout"))
}
