/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.amqp

import akka.util.Duration
import com.typesafe.config.Config
import java.util.concurrent.TimeUnit
import akka.actor._

class SettingsImpl(config: Config) extends Extension {
  final val Timeout: Duration = Duration(config.getMilliseconds("akka.amqp.timeout"), TimeUnit.MILLISECONDS)
}

abstract class Settings extends ExtensionId[SettingsImpl] with ExtensionIdProvider

object Settings extends Settings {

  override def lookup: Settings = this

  override def createExtension(system: ExtendedActorSystem): SettingsImpl = new SettingsImpl(system.settings.config)
}

