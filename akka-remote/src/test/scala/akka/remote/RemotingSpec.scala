/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import akka.actor._
import akka.pattern.ask
import akka.testkit._
import com.typesafe.config._
import scala.concurrent.Await
import scala.concurrent.Future
import akka.remote.transport.AssociationRegistry

object RemotingSpec {
  class Echo extends Actor {
    var target: ActorRef = context.system.deadLetters

    def receive = {
      case (p: Props, n: String) ⇒ sender ! context.actorOf(Props[Echo], n)
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

//TODO: remove debug stuff
@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class RemotingSpec extends AkkaSpec("""
akka {
  actor.provider = "akka.remote.RemoteActorRefProvider"
  remote.transport = "akka.remote.Remoting"

  remoting.transports = [
    {
      transport-class = "akka.remote.transport.TestTransport"
      settings {
        registry-key = aX33k0jWKg
        local-address = "test://RemotingSpec@localhost:12345"
        maximum-payload-bytes = 32000 bytes
        scheme-identifier = test
      }
    },
    {
      transport-class = "akka.remote.transport.TestTransport"
      settings {
        registry-key = AK1VFRUAHz
        local-address = "test2://RemotingSpec@localhost:12345"
        maximum-payload-bytes = 32000 bytes
        scheme-identifier = test2
      }
    }
  ]

  actor.deployment {
    /blub.remote = "test.akka://remote-sys@localhost:12346"
    /gonk.remote = "test2.akka://remote-sys@localhost:12346"
    /looker/child.remote = "test.akka://remote-sys@localhost:12346"
    /looker/child/grandchild.remote = "test.akka://RemotingSpec@localhost:12345"
  }
}
                                    """) with ImplicitSender with DefaultTimeout {

  import RemotingSpec._

  val conf = ConfigFactory.parseString(
    """
       akka.remote.netty.port=12346
       akka.remoting.transports = [
         {
           transport-class = "akka.remote.transport.TestTransport"
           settings {
             registry-key = aX33k0jWKg
             local-address = "test://remote-sys@localhost:12346"
             maximum-payload-bytes = 32000 bytes
             scheme-identifier = test
           }
         },
         {
           transport-class = "akka.remote.transport.TestTransport"
           settings {
             registry-key = AK1VFRUAHz
             local-address = "test2://remote-sys@localhost:12346"
             maximum-payload-bytes = 32000 bytes
             scheme-identifier = test2
           }
         }
      ]
    """).withFallback(system.settings.config)
  val other = ActorSystem("remote-sys", conf)

  // TODO: need a way to delay tests until all transports are registered
  Thread.sleep(500)

  val remote = other.actorOf(Props(new Actor {
    def receive = {
      case "ping" ⇒ sender ! (("pong", sender))
    }
  }), "echo")

  val here = system.actorFor("test.akka://remote-sys@localhost:12346/user/echo")

  override def atTermination() {
    other.shutdown()
    AssociationRegistry.clear()
  }

  "Remoting" must {

    "support remote look-ups" in {
      here ! "ping"
      expectMsgPF() {
        case ("pong", s: AnyRef) if s eq testActor ⇒ true
      }
    }

    // TODO: there is no lifecycle events and error reporting. Uncomment test when done.
    //    "send error message for wrong address" in {
    //      EventFilter.error(start = "dropping", occurrences = 1).intercept {
    //        system.actorFor("test.akka://remote-sys@localhost:12346/user/echo") ! "ping"
    //      }(other)
    //    }

    "support ask" in {
      Await.result(here ? "ping", timeout.duration) match {
        case ("pong", s: akka.pattern.PromiseActorRef) ⇒ // good
        case m                                         ⇒ fail(m + " was not (pong, AskActorRef)")
      }
    }

    "send dead letters on remote if actor does not exist" in {
      EventFilter.warning(pattern = "dead.*buh", occurrences = 1).intercept {
        system.actorFor("test.akka://remote-sys@localhost:12346/does/not/exist") ! "buh"
      }(other)
    }

    "create and supervise children on remote node" in {
      val r = system.actorOf(Props[Echo], "blub")
      r.path.toString must be === "test.akka://remote-sys@localhost:12346/remote/test.akka/RemotingSpec@localhost:12345/user/blub"
      r ! 42
      expectMsg(42)
      EventFilter[Exception]("crash", occurrences = 1).intercept {
        r ! new Exception("crash")
      }(other)
      expectMsg("preRestart")
      r ! 42
      expectMsg(42)
      system.stop(r)
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
      remref.asInstanceOf[ActorRefScope].isLocal must be(true)
      val myref = system.actorFor(system / "looker" / "child" / "grandchild")
      myref.isInstanceOf[RemoteActorRef] must be(true)
      myref ! 43
      expectMsg(43)
      lastSender must be theSameInstanceAs remref
      r.asInstanceOf[RemoteActorRef].getParent must be(l)
      system.actorFor("/user/looker/child") must be theSameInstanceAs r
      Await.result(l ? "child/..", timeout.duration).asInstanceOf[AnyRef] must be theSameInstanceAs l
      Await.result(system.actorFor(system / "looker" / "child") ? "..", timeout.duration).asInstanceOf[AnyRef] must be theSameInstanceAs l
    }

    "not fail ask across node boundaries" in {
      import system.dispatcher
      val f = for (_ ← 1 to 1000) yield here ? "ping" mapTo manifest[(String, ActorRef)]
      Await.result(Future.sequence(f), remaining).map(_._1).toSet must be(Set("pong"))
    }

    "be able to use multiple transports and use the appropriate one" in {
      val r = system.actorOf(Props[Echo], "gonk")
      r.path.toString must be === "test2.akka://remote-sys@localhost:12346/remote/test2.akka/RemotingSpec@localhost:12345/user/gonk"
      r ! 42
      expectMsg(42)
      EventFilter[Exception]("crash", occurrences = 1).intercept {
        r ! new Exception("crash")
      }(other)
      expectMsg("preRestart")
      r ! 42
      expectMsg(42)
      system.stop(r)
      expectMsg("postStop")
    }

  }

}
