/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import akka.testkit._
import akka.actor._
import com.typesafe.config._
import scala.concurrent.Future
import scala.concurrent.Await
import scala.reflect.classTag
import akka.pattern.ask
import akka.event.Logging

object RemoteCommunicationSpec {
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

  class NotSerializable(val x: String) {
    override def toString: String = x
  }

  class LargeMessage extends Serializable {
    val body: Array[Byte] = Array.ofDim[Byte](1024 * 1024)
    override def toString: String = "LargeMessage"
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class RemoteCommunicationSpec extends RemoteCommunicationBaseSpec("")

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class RemoteCommunicationSecureCookiePassiveSpec extends RemoteCommunicationBaseSpec("""
akka.remote.netty {
  secure-cookie = "keepitsecretkeepitsafe"
  require-cookie = on
  use-passive-connections = on
}""")

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class RemoteCommunicationSecureCookieActiveSpec extends RemoteCommunicationBaseSpec("""
akka.remote.netty {
  secure-cookie = "keepitsecretkeepitsafe"
  require-cookie = on
  use-passive-connections = off
}""")

abstract class RemoteCommunicationBaseSpec(config: String) extends AkkaSpec(ConfigFactory.parseString(config).withFallback(ConfigFactory.parseString("""
  akka {
  actor.provider = "akka.remote.RemoteActorRefProvider"
  remote.netty {
    hostname = localhost
    port = 12345
  }
  actor.deployment {
    /blub.remote = "akka://remote-sys@localhost:12346"
    /looker/child.remote = "akka://remote-sys@localhost:12346"
    /looker/child/grandchild.remote = "akka://RemoteCommunicationBaseSpec@localhost:12345"
  }
}
"""))) with ImplicitSender with DefaultTimeout {

  import RemoteCommunicationSpec._

  val conf = ConfigFactory.parseString("akka.remote.netty.port=12346").withFallback(system.settings.config)
  val other = ActorSystem("remote-sys", conf)

  val remote = other.actorOf(Props(new Actor {
    def receive = {
      case "ping" ⇒ sender ! (("pong", sender))
    }
  }), "echo")

  val here = system.actorFor("akka://remote-sys@localhost:12346/user/echo")

  override def atTermination() {
    other.shutdown()
  }

  "Remoting" must {

    "support remote look-ups" in {
      here ! "ping"
      expectMsgPF() {
        case ("pong", s: AnyRef) if s eq testActor ⇒ true
      }
    }

    "send error message for wrong address" in {
      val old = other.eventStream.logLevel
      other.eventStream.setLogLevel(Logging.DebugLevel)
      EventFilter.debug(start = "dropping", occurrences = 1).intercept {
        system.actorFor("akka://remotesys@localhost:12346/user/echo") ! "ping"
      }(other)
      other.eventStream.setLogLevel(old)
    }

    "support ask" in {
      Await.result(here ? "ping", timeout.duration) match {
        case ("pong", s: akka.pattern.PromiseActorRef) ⇒ // good
        case m                                         ⇒ fail(m + " was not (pong, AskActorRef)")
      }
    }

    "send dead letters on remote if actor does not exist" in {
      EventFilter.warning(pattern = "dead.*buh", occurrences = 1).intercept {
        system.actorFor("akka://remote-sys@localhost:12346/does/not/exist") ! "buh"
      }(other)
    }

    "create and supervise children on remote node" in {
      val r = system.actorOf(Props[Echo], "blub")
      r.path.toString must be === "akka://remote-sys@localhost:12346/remote/RemoteCommunicationBaseSpec@localhost:12345/user/blub"
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
      val f = for (_ ← 1 to 1000) yield here ? "ping" mapTo classTag[(String, ActorRef)]
      Await.result(Future.sequence(f), remaining).map(_._1).toSet must be(Set("pong"))
    }

    "report not serializable messages without closing the channel" in {
      system.eventStream.subscribe(testActor, classOf[RemoteLifeCycleEvent])

      filterEvents(
        EventFilter[Exception](pattern = "Remote message.*could not be delivered", occurrences = 1),
        EventFilter.warning(pattern = "dead.*I can't fly", occurrences = 1)) {
          here ! new NotSerializable("I can't fly")
        }
      expectNoMsg()
    }

    "report oversized messages without closing the channel" in {
      system.eventStream.subscribe(testActor, classOf[RemoteLifeCycleEvent])

      filterEvents(
        EventFilter[Exception](pattern = "Remote message.*could not be delivered", occurrences = 1),
        EventFilter.warning(pattern = "dead.*LargeMessage", occurrences = 1)) {
          here ! new LargeMessage
        }
      expectNoMsg()
    }
  }

}
