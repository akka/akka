/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import com.typesafe.config.Config
import scala.util.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.actor._

object BeanstalkBasedMailboxExtension extends ExtensionId[BeanstalkMailboxSettings] with ExtensionIdProvider {
  override def get(system: ActorSystem): BeanstalkMailboxSettings = super.get(system)
  def lookup() = this
  def createExtension(system: ExtendedActorSystem) = new BeanstalkMailboxSettings(system.settings.config)
}

class BeanstalkMailboxSettings(val config: Config) extends Extension {

  import config._

  val Hostname = getString("akka.actor.mailbox.beanstalk.hostname")
  val Port = getInt("akka.actor.mailbox.beanstalk.port")
  val ReconnectWindow = Duration(getMilliseconds("akka.actor.mailbox.beanstalk.reconnect-window"), MILLISECONDS)
  val MessageSubmitDelay = Duration(getMilliseconds("akka.actor.mailbox.beanstalk.message-submit-delay"), MILLISECONDS)
  val MessageSubmitTimeout = Duration(getMilliseconds("akka.actor.mailbox.beanstalk.message-submit-timeout"), MILLISECONDS)
  val MessageTimeToLive = Duration(getMilliseconds("akka.actor.mailbox.beanstalk.message-time-to-live"), MILLISECONDS)

}