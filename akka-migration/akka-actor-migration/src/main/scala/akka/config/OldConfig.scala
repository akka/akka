/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.config
import akka.actor.GlobalActorSystem

/**
 * Migration replacement for `object akka.config.Config`.
 */
object OldConfig {

  val config: com.typesafe.config.Config = GlobalActorSystem.settings.config

}