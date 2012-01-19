/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.testkit._
import akka.actor.ActorSystemImpl
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigResolveOptions
import java.io.File

object AkkaRemoteSpec {
  private def configParseOptions = ConfigParseOptions.defaults.setAllowMissing(false)

  val testConf: Config = {
    System.getProperty("akka.config") match {
      case null ⇒ AkkaSpec.testConf
      case location ⇒
        ConfigFactory.systemProperties
          .withFallback(ConfigFactory.parseFileAnySyntax(new File(location), configParseOptions))
          .withFallback(ConfigFactory.defaultReference).resolve(ConfigResolveOptions.defaults)
    }
  }

  val testNodes = System.getProperty("test.hosts")
}

abstract class AkkaRemoteSpec(config: Config)
  extends AkkaSpec(config.withFallback(AkkaRemoteSpec.testConf))
  with MultiJvmSync {

  /**
   * Helper function for accessing the underlying remoting.
   */
  def remote: Remote = {
    system.asInstanceOf[ActorSystemImpl].provider match {
      case r: RemoteActorRefProvider ⇒ r.remote
      case _                         ⇒ throw new Exception("Remoting is not enabled")
    }
  }

}
