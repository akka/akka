/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit

import com.typesafe.config.Config
import scala.concurrent.util.Duration
import akka.util.Timeout
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.actor.{ ExtensionId, ActorSystem, Extension, ExtendedActorSystem }
import scala.concurrent.util.FiniteDuration

object TestKitExtension extends ExtensionId[TestKitSettings] {
  override def get(system: ActorSystem): TestKitSettings = super.get(system)
  def createExtension(system: ExtendedActorSystem): TestKitSettings = new TestKitSettings(system.settings.config)
}

class TestKitSettings(val config: Config) extends Extension {

  import config._

  val TestTimeFactor = getDouble("akka.test.timefactor")
  val SingleExpectDefaultTimeout: FiniteDuration = Duration(getMilliseconds("akka.test.single-expect-default"), MILLISECONDS)
  val TestEventFilterLeeway: FiniteDuration = Duration(getMilliseconds("akka.test.filter-leeway"), MILLISECONDS)
  val DefaultTimeout: Timeout = Timeout(Duration(getMilliseconds("akka.test.default-timeout"), MILLISECONDS))
}