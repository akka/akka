/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.testkit._
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import com.typesafe.config.ConfigResolveOptions
import java.io.File
import akka.actor.ActorSystem

object AkkaRemoteSpec {
  private def configParseOptions = ConfigParseOptions.defaults.setAllowMissing(false)

  val testConf: Config = {
    System.getProperty("akka.config") match {
      case null ⇒ AkkaSpec.testConf
      case location ⇒
        ConfigFactory.systemProperties
          .withFallback(ConfigFactory.parseFileAnySyntax(new File(location), configParseOptions))
          .withFallback(ConfigFactory.defaultReference(ActorSystem.findClassLoader())).resolve(ConfigResolveOptions.defaults)
    }
  }

  val testNodes = System.getProperty("test.hosts")
}

abstract class AkkaRemoteSpec(config: Config)
  extends AkkaSpec(config.withFallback(AkkaRemoteSpec.testConf))
  with MultiJvmSync
