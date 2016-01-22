package akka.http.javadsl.settings

import akka.actor.ActorSystem
import com.typesafe.config.Config

trait SettingsCompanion[T] {

  /**
   * Creates an instance of settings using the configuration provided by the given ActorSystem.
   *
   * Java API
   */
  final def create(system: ActorSystem): T = create(system.settings.config)

  /**
   * Creates an instance of settings using the given Config.
   *
   * Java API
   */
  def create(config: Config): T

  /**
   * Create an instance of settings using the given String of config overrides to override
   * settings set in the class loader of this class (i.e. by application.conf or reference.conf files in
   * the class loader of this class).
   *
   * Java API
   */
  def create(configOverrides: String): T
}
