/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import akka.testkit._
import akka.actor._
import com.typesafe.config._

object RemoteCommunicationSpec {
  val echo = Props(ctx ⇒ { case x ⇒ ctx.sender ! x })
}

class RemoteCommunicationSpec extends AkkaSpec("""
akka {
  actor.provider = "akka.remote.RemoteActorRefProvider"
  cluster.nodename = Nonsense
  loglevel = DEBUG
  remote.server {
    hostname = localhost
    port = 12345
  }
  actor.deployment {
    /user/blub {
      remote.nodes = ["remote_sys@localhost:12346"]
    }
  }
}
""") with ImplicitSender {

  import RemoteCommunicationSpec._

  val conf = ConfigFactory.parseString("akka.remote.server.port=12346").withFallback(system.settings.config)
  val other = ActorSystem("remote_sys", conf)

  system.eventStream.subscribe(system.actorFor("/system/log1-TestEventListener"), classOf[RemoteLifeCycleEvent])
  other.eventStream.subscribe(other.actorFor("/system/log1-TestEventListener"), classOf[RemoteLifeCycleEvent])

  val remote = other.actorOf(Props(new Actor {
    def receive = {
      case "ping" ⇒ sender ! (("pong", sender))
    }
  }), "echo")

  val here = system.actorFor("akka://remote_sys@localhost:12346/user/echo")

  implicit val timeout = system.settings.ActorTimeout

  override def atTermination() {
    other.stop()
  }

  "Remoting" must {

    "support remote look-ups" in {
      here ! "ping"
      expectMsgPF() {
        case ("pong", s: AnyRef) if s eq testActor ⇒ true
      }
    }

    "send error message for wrong address" in {
      EventFilter.error(start = "dropping", occurrences = 1).intercept {
        system.actorFor("akka://remotesys@localhost:12346/user/echo") ! "ping"
      }(other)
    }

    "support ask" in {
      (here ? "ping").get match {
        case ("pong", s: AskActorRef) ⇒ // good
        case m                        ⇒ fail(m + " was not (pong, AskActorRef)")
      }
    }

    "send dead letters on remote if actor does not exist" in {
      EventFilter.warning(pattern = "dead.*buh", occurrences = 1).intercept {
        system.actorFor("akka://remote_sys@localhost:12346/does/not/exist") ! "buh"
      }(other)
    }

    "create children on remote node" in {
      val r = system.actorOf(echo, "blub")
      r.path.toString must be === "akka://remote_sys@localhost:12346/remote/RemoteCommunicationSpec@localhost:12345/user/blub"
      r ! 42
      expectMsg(42)
    }

  }

}