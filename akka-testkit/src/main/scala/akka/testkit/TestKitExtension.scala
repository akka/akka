/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit

import com.typesafe.config.Config
import scala.concurrent.duration.Duration
import akka.util.Timeout
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.actor.{ ExtensionId, ActorSystem, Extension, ExtendedActorSystem }
import scala.concurrent.duration.FiniteDuration

object TestKitExtension extends ExtensionId[TestKitSettings] {
  override def get(system: ActorSystem): TestKitSettings = super.get(system)
  def createExtension(system: ExtendedActorSystem): TestKitSettings = new TestKitSettings(system.settings.config)
}

class TestKitSettings(val config: Config) extends Extension {

  import akka.util.Helpers.ConfigOps

  val TestTimeFactor = config.getDouble("akka.test.timefactor")
  val SingleExpectDefaultTimeout: FiniteDuration = config.getMillisDuration("akka.test.single-expect-default")
  val TestEventFilterLeeway: FiniteDuration = config.getMillisDuration("akka.test.filter-leeway")
  val DefaultTimeout: Timeout = Timeout(config.getMillisDuration("akka.test.default-timeout"))
}
