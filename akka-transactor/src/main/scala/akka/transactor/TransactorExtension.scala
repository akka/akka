/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.transactor

import akka.actor.{ ActorSystem, ExtensionId, ExtensionIdProvider, ExtendedActorSystem }
import akka.actor.Extension
import com.typesafe.config.Config
import akka.util.Timeout
import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS

/**
 * TransactorExtension is an Akka Extension to hold settings for transactors.
 */
object TransactorExtension extends ExtensionId[TransactorSettings] with ExtensionIdProvider {
  override def get(system: ActorSystem): TransactorSettings = super.get(system)
  override def lookup: TransactorExtension.type = TransactorExtension
  override def createExtension(system: ExtendedActorSystem): TransactorSettings = new TransactorSettings(system.settings.config)
}

class TransactorSettings(val config: Config) extends Extension {
  import config._
  val CoordinatedTimeout: Timeout = Timeout(Duration(getMilliseconds("akka.transactor.coordinated-timeout"), MILLISECONDS))
}