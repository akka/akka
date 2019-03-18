/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit

import com.typesafe.config.Config
import akka.util.Timeout
import akka.actor.{ ActorSystem, ExtendedActorSystem, Extension, ExtensionId }
import scala.concurrent.duration.FiniteDuration

object TestKitExtension extends ExtensionId[TestKitSettings] {
  override def get(system: ActorSystem): TestKitSettings = super.get(system)
  def createExtension(system: ExtendedActorSystem): TestKitSettings = new TestKitSettings(system.settings.config)
}

class TestKitSettings(val config: Config) extends Extension {

  import akka.util.Helpers._

  val TestTimeFactor = config
    .getDouble("akka.test.timefactor")
    .requiring(tf => !tf.isInfinite && tf > 0, "akka.test.timefactor must be positive finite double")
  val SingleExpectDefaultTimeout: FiniteDuration = config.getMillisDuration("akka.test.single-expect-default")
  val TestEventFilterLeeway: FiniteDuration = config.getMillisDuration("akka.test.filter-leeway")
  val DefaultTimeout: Timeout = Timeout(config.getMillisDuration("akka.test.default-timeout"))
}
