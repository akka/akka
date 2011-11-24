/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigRoot
import akka.util.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.actor.{ ExtensionProvider, ActorSystem, Extension, ActorSystemImpl }

object BeanstalkBasedMailboxExtension extends Extension[BeanstalkMailboxSettings] with ExtensionProvider {
  def lookup() = this
  def createExtension(system: ActorSystemImpl) = new BeanstalkMailboxSettings(system.applicationConfig)
}

class BeanstalkMailboxSettings(cfg: Config) {
  private def referenceConfig: Config =
    ConfigFactory.parseResource(classOf[ActorSystem], "/akka-beanstalk-mailbox-reference.conf",
      ConfigParseOptions.defaults.setAllowMissing(false))
  val config: ConfigRoot = ConfigFactory.emptyRoot("akka-beanstalk-mailbox").withFallback(cfg).withFallback(referenceConfig).resolve()

  import config._

  val Hostname = getString("akka.actor.mailbox.beanstalk.hostname")
  val Port = getInt("akka.actor.mailbox.beanstalk.port")
  val ReconnectWindow = Duration(getMilliseconds("akka.actor.mailbox.beanstalk.reconnect-window"), MILLISECONDS)
  val MessageSubmitDelay = Duration(getMilliseconds("akka.actor.mailbox.beanstalk.message-submit-delay"), MILLISECONDS)
  val MessageSubmitTimeout = Duration(getMilliseconds("akka.actor.mailbox.beanstalk.message-submit-timeout"), MILLISECONDS)
  val MessageTimeToLive = Duration(getMilliseconds("akka.actor.mailbox.beanstalk.message-time-to-live"), MILLISECONDS)

}