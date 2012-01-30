/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */
package akka.remote

import akka.testkit._
import akka.actor.{ ActorSystem, DeathWatchSpec }
import com.typesafe.config.ConfigFactory

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class RemoteDeathWatchSpec extends AkkaSpec(ConfigFactory.parseString("""
akka {
    actor {
        provider = "akka.remote.RemoteActorRefProvider"
        deployment {
            /watchers.remote = "akka://other@127.0.0.1:2666"
        }
    }
    remote.netty {
        hostname = "127.0.0.1"
        port = 0
    }
}
""")) with ImplicitSender with DefaultTimeout with DeathWatchSpec {

  val other = ActorSystem("other", ConfigFactory.parseString("akka.remote.netty.port=2666").withFallback(system.settings.config))

  override def atTermination() {
    other.shutdown()
  }

}
