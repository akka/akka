/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.config

import org.scalatest.WordSpec
import org.scalatest.Matchers
import akka.testkit.TestKit

//#imports
import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
//#imports

class ConfigDocSpec extends WordSpec with Matchers {

  "programmatically configure ActorSystem" in {
    //#custom-config
    val customConf = ConfigFactory.parseString("""
      akka.actor.deployment {
        /my-service {
          router = round-robin
          nr-of-instances = 3
        }
      }
      """)
    // ConfigFactory.load sandwiches customConfig between default reference
    // config and default overrides, and then resolves it.
    val system = ActorSystem("MySystem", ConfigFactory.load(customConf))
    //#custom-config

    TestKit.shutdownActorSystem(system)
  }
}
