/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import akka.testkit._
import akka.actor._
import com.typesafe.config._

object RemoteCommunicationSpec {
  class Echo extends Actor {
    var target: ActorRef = context.system.deadLetters

    def receive = {
      case (p: Props, n: String) ⇒ sender ! context.actorOf[Echo](n)
      case ex: Exception         ⇒ throw ex
      case s: String             ⇒ sender ! context.actorFor(s)
      case x                     ⇒ target = sender; sender ! x
    }

    override def preStart() {}
    override def preRestart(cause: Throwable, msg: Option[Any]) {
      target ! "preRestart"
    }
    override def postRestart(cause: Throwable) {}
    override def postStop() {
      target ! "postStop"
    }
  }
}

class RemoteCommunicationSpec extends AkkaSpec("""
akka {
  actor.provider = "akka.remote.RemoteActorRefProvider"
  cluster.nodename = Nonsense
  remote.server {
    hostname = localhost
    port = 12345
  }
  actor.deployment {
    /user/blub.remote.nodes = ["remote_sys@localhost:12346"]
    /user/looker/child.remote.nodes = ["remote_sys@localhost:12346"]
    /user/looker/child/grandchild.remote.nodes = ["RemoteCommunicationSpec@localhost:12345"]
  }
}
""") with ImplicitSender {

  import RemoteCommunicationSpec._

  val conf = ConfigFactory.parseString("akka.remote.server.port=12346").withFallback(system.settings.config)
  val other = ActorSystem("remote_sys", conf)

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

    "create and supervise children on remote node" in {
      val r = system.actorOf[Echo]("blub")
      r.path.toString must be === "akka://remote_sys@localhost:12346/remote/RemoteCommunicationSpec@localhost:12345/user/blub"
      r ! 42
      expectMsg(42)
      EventFilter[Exception]("crash", occurrences = 1).intercept {
        r ! new Exception("crash")
      }(other)
      expectMsg("preRestart")
      r ! 42
      expectMsg(42)
      r.stop()
      expectMsg("postStop")
    }

    "look-up actors across node boundaries" in {
      val l = system.actorOf(Props(new Actor {
        def receive = {
          case (p: Props, n: String) ⇒ sender ! context.actorOf(p, n)
          case s: String             ⇒ sender ! context.actorFor(s)
        }
      }), "looker")
      l ! (Props[Echo], "child")
      val r = expectMsgType[ActorRef]
      r ! (Props[Echo], "grandchild")
      val remref = expectMsgType[ActorRef]
      remref.isInstanceOf[LocalActorRef] must be(true)
      val myref = system.actorFor(system / "looker" / "child" / "grandchild")
      myref.isInstanceOf[RemoteActorRef] must be(true)
      myref ! 43
      expectMsg(43)
      lastSender must be theSameInstanceAs remref
      (l ? "child/..").as[ActorRef].get must be theSameInstanceAs l
      (system.actorFor(system / "looker" / "child") ? "..").as[ActorRef].get must be theSameInstanceAs l
    }

  }

}