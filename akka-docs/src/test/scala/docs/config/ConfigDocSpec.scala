/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
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
          router = round-robin-pool
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

  "deployment section" in {
    val conf =
      ConfigFactory.parseString("""
  #//#deployment-section
  akka.actor.deployment {
  
    # '/user/actorA/actorB' is a remote deployed actor
    /actorA/actorB {
      remote = "akka://sampleActorSystem@127.0.0.1:2553"
    }
    
    # all direct children of '/user/actorC' have a dedicated dispatcher 
    "/actorC/*" {
      dispatcher = my-dispatcher
    }

    # all descendants of '/user/actorC' (direct children, and their children recursively)
    # have a dedicated dispatcher
    "/actorC/**" {
      dispatcher = my-dispatcher
    }
    
    # '/user/actorD/actorE' has a special priority mailbox
    /actorD/actorE {
      mailbox = prio-mailbox
    }
    
    # '/user/actorF/actorG/actorH' is a random pool
    /actorF/actorG/actorH {
      router = random-pool
      nr-of-instances = 5
    }
  }
  
  my-dispatcher {
    fork-join-executor.parallelism-min = 10
    fork-join-executor.parallelism-max = 10
  }
  prio-mailbox {
    mailbox-type = "a.b.MyPrioMailbox"
  }
  #//#deployment-section
  """)
    val system = ActorSystem("MySystem", conf)
    TestKit.shutdownActorSystem(system)
  }
}
