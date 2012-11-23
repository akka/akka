/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
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
            /watchers.remote = "tcp.akka://other@localhost:2666"
        }
    }
    remoting.tcp {
        hostname = "localhost"
        port = 0
    }
}
                                                                      """)) with ImplicitSender with DefaultTimeout with DeathWatchSpec {

  val other = ActorSystem("other", ConfigFactory.parseString("akka.remoting.transports.tcp.port=2666")
    .withFallback(system.settings.config))

  override def atTermination() {
    other.shutdown()
  }

}
