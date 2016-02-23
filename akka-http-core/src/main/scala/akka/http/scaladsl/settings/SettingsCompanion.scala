/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.scaladsl.settings

import akka.actor.{ ActorRefFactory, ActorSystem }
import com.typesafe.config.Config
import akka.http.impl.util._

/** INTERNAL API */
private[akka] trait SettingsCompanion[T] {

  /**
   * Creates an instance of settings using the configuration provided by the given ActorSystem.
   */
  final def apply(system: ActorSystem): T = apply(system.settings.config)
  implicit def default(implicit system: ActorRefFactory): T = apply(actorSystem)

  /**
   * Creates an instance of settings using the given Config.
   */
  def apply(config: Config): T

  /**
   * Create an instance of settings using the given String of config overrides to override
   * settings set in the class loader of this class (i.e. by application.conf or reference.conf files in
   * the class loader of this class).
   */
  def apply(configOverrides: String): T
}
