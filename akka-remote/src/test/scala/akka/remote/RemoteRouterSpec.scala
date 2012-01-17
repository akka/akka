/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import akka.testkit._
import akka.routing._
import akka.actor._
import com.typesafe.config._

object RemoteRouterSpec {
  class Echo extends Actor {
    def receive = {
      case _ ⇒ sender ! self.path
    }
  }
}

class RemoteRouterSpec extends AkkaSpec("""
akka {
  actor.provider = "akka.remote.RemoteActorRefProvider"
  remote.server {
    hostname = localhost
    port = 12345
  }
  actor.deployment {
    /blub {
      router = "round-robin"
      nr-of-instances = 2
      target.nodes = ["akka://remote_sys@localhost:12346"]
    }
  }
}
""") with ImplicitSender {

  import RemoteRouterSpec._

  val conf = ConfigFactory.parseString("akka.remote.server.port=12346").withFallback(system.settings.config)
  val other = ActorSystem("remote_sys", conf)

  override def atTermination() {
    other.shutdown()
  }

  "A Remote Router" must {

    "deploy its children on remote host driven by configuration" in {
      val router = system.actorOf(Props[Echo].withRouter(RoundRobinRouter(2)), "blub")
      router ! ""
      expectMsgType[ActorPath].toString must be === "akka://remote_sys@localhost:12346/remote/RemoteRouterSpec@localhost:12345/user/blub/c1"
      router ! ""
      expectMsgType[ActorPath].toString must be === "akka://remote_sys@localhost:12346/remote/RemoteRouterSpec@localhost:12345/user/blub/c2"
    }

  }

}
