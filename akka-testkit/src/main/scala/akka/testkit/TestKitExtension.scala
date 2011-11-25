/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigRoot
import akka.util.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.actor.{ ExtensionId, ActorSystem, Extension, ActorSystemImpl }

object TestKitExtension extends ExtensionId[TestKitSettings] {
  def createExtension(system: ActorSystemImpl): TestKitSettings = new TestKitSettings(system.applicationConfig)
}

class TestKitSettings(cfg: Config) extends Extension {
  private def referenceConfig: Config =
    ConfigFactory.parseResource(classOf[ActorSystem], "/akka-testkit-reference.conf",
      ConfigParseOptions.defaults.setAllowMissing(false))
  val config: ConfigRoot = ConfigFactory.emptyRoot("akka-testkit").withFallback(cfg).withFallback(referenceConfig).resolve()

  import config._

  val TestTimeFactor = getDouble("akka.test.timefactor")
  val SingleExpectDefaultTimeout = Duration(getMilliseconds("akka.test.single-expect-default"), MILLISECONDS)
  val TestEventFilterLeeway = Duration(getMilliseconds("akka.test.filter-leeway"), MILLISECONDS)
}