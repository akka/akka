/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import akka.testkit._
import akka.routing._
import akka.actor._
import com.typesafe.config._

object RemoteRouterSpec {
  class Echo extends Actor {
    def receive = {
      case _ ⇒ sender ! self
    }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class RemoteRouterSpec extends AkkaSpec("""
akka {
  actor.provider = "akka.remote.RemoteActorRefProvider"
  remote.netty {
    hostname = localhost
    port = 12345
  }
  actor.deployment {
    /blub {
      router = round-robin
      nr-of-instances = 2
      target.nodes = ["akka://remote_sys@localhost:12346"]
    }
    /elastic-blub {
      router = round-robin
      resizer {
        lower-bound = 2
        upper-bound = 3
      }
      target.nodes = ["akka://remote_sys@localhost:12346"]
    }
  }
}
""") with ImplicitSender {

  import RemoteRouterSpec._

  val conf = ConfigFactory.parseString("akka.remote.netty.port=12346").withFallback(system.settings.config)
  val other = ActorSystem("remote_sys", conf)

  override def atTermination() {
    other.shutdown()
  }

  "A Remote Router" must {

    "deploy its children on remote host driven by configuration" in {
      val router = system.actorOf(Props[Echo].withRouter(RoundRobinRouter(2)), "blub")
      router ! ""
      expectMsgType[ActorRef].path.toString must be === "akka://remote_sys@localhost:12346/remote/RemoteRouterSpec@localhost:12345/user/blub/c1"
      router ! ""
      expectMsgType[ActorRef].path.toString must be === "akka://remote_sys@localhost:12346/remote/RemoteRouterSpec@localhost:12345/user/blub/c2"
    }

    "deploy its children on remote host driven by programatic definition" in {
      val router = system.actorOf(Props[Echo].withRouter(new RemoteRouterConfig(RoundRobinRouter(2),
        Seq("akka://remote_sys@localhost:12346"))), "blub2")
      router ! ""
      expectMsgType[ActorRef].path.toString must be === "akka://remote_sys@localhost:12346/remote/RemoteRouterSpec@localhost:12345/user/blub2/c1"
      router ! ""
      expectMsgType[ActorRef].path.toString must be === "akka://remote_sys@localhost:12346/remote/RemoteRouterSpec@localhost:12345/user/blub2/c2"
    }

    "deploy dynamic resizable number of children on remote host driven by configuration" in {
      val router = system.actorOf(Props[Echo].withRouter(FromConfig), "elastic-blub")
      router ! ""
      expectMsgType[ActorRef].path.toString must be === "akka://remote_sys@localhost:12346/remote/RemoteRouterSpec@localhost:12345/user/elastic-blub/c1"
      router ! ""
      expectMsgType[ActorRef].path.toString must be === "akka://remote_sys@localhost:12346/remote/RemoteRouterSpec@localhost:12345/user/elastic-blub/c2"
    }

  }

}
