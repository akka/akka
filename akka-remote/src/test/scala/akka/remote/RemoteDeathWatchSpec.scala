/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import akka.testkit._
import akka.actor._
import com.typesafe.config.ConfigFactory
import akka.actor.RootActorPath
import scala.concurrent.duration._

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class RemoteDeathWatchSpec extends AkkaSpec(ConfigFactory.parseString("""
akka {
    actor {
        provider = "akka.remote.RemoteActorRefProvider"
        deployment {
            /watchers.remote = "akka.tcp://other@localhost:2666"
        }
    }
    remote.netty.tcp {
        hostname = "localhost"
        port = 0
        server-socket-worker-pool.pool-size-max = 2
        client-socket-worker-pool.pool-size-max = 2
    }
}
""")) with ImplicitSender with DefaultTimeout with DeathWatchSpec {

  val other = ActorSystem("other", ConfigFactory.parseString("akka.remote.netty.tcp.port=2666")
    .withFallback(system.settings.config))

  override def beforeTermination() {
    system.eventStream.publish(TestEvent.Mute(
      EventFilter.warning(pattern = "received dead letter.*Disassociate")))
  }

  override def afterTermination() {
    shutdown(other)
  }

  "receive Terminated when watched node is unknown host" in {
    val path = RootActorPath(Address("akka.tcp", system.name, "unknownhost", 2552)) / "user" / "subject"
    system.actorOf(Props(new Actor {
      context.watch(context.actorFor(path))
      def receive = {
        case t: Terminated ⇒ testActor ! t.actor.path
      }
    }), name = "observer2")

    expectMsg(60.seconds, path)
  }

  "receive ActorIdentity(None) when identified node is unknown host" in {
    val path = RootActorPath(Address("akka.tcp", system.name, "unknownhost2", 2552)) / "user" / "subject"
    system.actorSelection(path) ! Identify(path)
    expectMsg(60.seconds, ActorIdentity(path, None))
  }

}
