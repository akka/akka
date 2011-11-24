/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit

import akka.actor.ActorSystem
import akka.actor.ExtensionKey
import akka.actor.Extension
import akka.actor.ActorSystemImpl
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigRoot
import akka.util.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS

object TestKitExtensionKey extends ExtensionKey[TestKitExtension]

object TestKitExtension {
  def apply(system: ActorSystem): TestKitExtension = {
    if (!system.hasExtension(TestKitExtensionKey)) {
      system.registerExtension(new TestKitExtension)
    }
    system.extension(TestKitExtensionKey)
  }

  class Settings(cfg: Config) {
    private def referenceConfig: Config =
      ConfigFactory.parseResource(classOf[ActorSystem], "/akka-testkit-reference.conf",
        ConfigParseOptions.defaults.setAllowMissing(false))
    val config: ConfigRoot = ConfigFactory.emptyRoot("akka-testkit").withFallback(cfg).withFallback(referenceConfig).resolve()

    import config._

    val TestTimeFactor = getDouble("akka.test.timefactor")
    val SingleExpectDefaultTimeout = Duration(getMilliseconds("akka.test.single-expect-default"), MILLISECONDS)
    val TestEventFilterLeeway = Duration(getMilliseconds("akka.test.filter-leeway"), MILLISECONDS)

  }
}

class TestKitExtension extends Extension[TestKitExtension] {
  import TestKitExtension._
  @volatile
  private var _settings: Settings = _

  def key = TestKitExtensionKey

  def init(system: ActorSystemImpl) {
    _settings = new Settings(system.applicationConfig)
  }

  def settings: Settings = _settings

}