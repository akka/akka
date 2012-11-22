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
import scala.concurrent.duration._
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

  val cfg: Config = ConfigFactory parseString ("""
    common-transport-settings {
      log-transport-events = true
      connection-timeout = 120s
      use-dispatcher-for-io = ""
      write-buffer-high-water-mark = 0b
      write-buffer-low-water-mark = 0b
      send-buffer-size = 32000b
      receive-buffer-size = 32000b
      backlog = 4096
      hostname = localhost
      enable-ssl = false

      server-socket-worker-pool {
        pool-size-min = 2
        pool-size-factor = 1.0
        pool-size-max = 8
      }

      client-socket-worker-pool {
        pool-size-min = 2
        pool-size-factor = 1.0
        pool-size-max = 8
      }
    }

    common-ssl-settings {
      key-store = "%s"
      trust-store = "%s"
      key-store-password = "changeme"
      trust-store-password = "changeme"
      protocol = "TLSv1"
      random-number-generator = "AES128CounterSecureRNG"
      enabled-algorithms = [TLS_RSA_WITH_AES_128_CBC_SHA]
      sha1prng-random-source = "/dev/./urandom"
    }

    akka {
      actor.provider = "akka.remote.RemoteActorRefProvider"
      remote.transport = "akka.remote.Remoting"

      remoting.retry-latch-closed-for = 1 s
      remoting.log-remote-lifecycle-events = on

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
          transport-class = "akka.remote.transport.netty.NettyTransport"
          settings = ${common-transport-settings}
          settings {
            transport-protocol = tcp
            port = 12345
          }
        },
        {
          transport-class = "akka.remote.transport.netty.NettyTransport"
          settings = ${common-transport-settings}
          settings {
            transport-protocol = udp
            port = 12345
          }
        },
        {
          transport-class = "akka.remote.transport.netty.NettyTransport"
          settings = ${common-transport-settings}
          settings {
            transport-protocol = tcp
            enable-ssl = true
            port = 23456
            ssl = ${common-ssl-settings}
          }
        }
      ]

      actor.deployment {
        /blub.remote = "test.akka://remote-sys@localhost:12346"
        /gonk.remote = "tcp.akka://remote-sys@localhost:12346"
        /zagzag.remote = "udp.akka://remote-sys@localhost:12346"
        /roghtaar.remote = "tcp.ssl.akka://remote-sys@localhost:23457"
        /looker/child.remote = "test.akka://remote-sys@localhost:12346"
        /looker/child/grandchild.remote = "test.akka://RemotingSpec@localhost:12345"
      }
    }
""".format(
    getClass.getClassLoader.getResource("keystore").getPath,
    getClass.getClassLoader.getResource("truststore").getPath))

}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class RemotingSpec extends AkkaSpec(RemotingSpec.cfg) with ImplicitSender with DefaultTimeout {

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
           transport-class = "akka.remote.transport.netty.NettyTransport"
           settings = ${common-transport-settings}
           settings {
             transport-protocol = tcp
             port = 12346
           }
         },
         {
           transport-class = "akka.remote.transport.netty.NettyTransport"
           settings = ${common-transport-settings}
           settings {
             transport-protocol = udp
             port = 12346
           }
         },
         {
           transport-class = "akka.remote.transport.netty.NettyTransport"
           settings = ${common-transport-settings}
           settings {
             transport-protocol = tcp
             enable-ssl = true
             port = 23457
             ssl = ${common-ssl-settings}
           }
         }
      ]
    """).withFallback(system.settings.config).resolve()
  val other = ActorSystem("remote-sys", conf)

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
      expectMsg("pong")
      lastSender must be(testActor)
    }

    "send error message for wrong address" in {
      EventFilter.error(start = "AssociationError", occurrences = 1).intercept {
        system.actorFor("test.akka://nonexistingsystem@localhost:12346/user/echo") ! "ping"
      }
    }

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

    "be able to use multiple transports and use the appropriate one (TCP)" in {
      val r = system.actorOf(Props[Echo], "gonk")
      r.path.toString must be === "tcp.akka://remote-sys@localhost:12346/remote/tcp.akka/RemotingSpec@localhost:12345/user/gonk"
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

    "be able to use multiple transports and use the appropriate one (UDP)" in {
      val r = system.actorOf(Props[Echo], "zagzag")
      r.path.toString must be === "udp.akka://remote-sys@localhost:12346/remote/udp.akka/RemotingSpec@localhost:12345/user/zagzag"
      r ! 42
      expectMsg(10 seconds, 42)
      EventFilter[Exception]("crash", occurrences = 1).intercept {
        r ! new Exception("crash")
      }(other)
      expectMsg("preRestart")
      r ! 42
      expectMsg(42)
      system.stop(r)
      expectMsg("postStop")
    }

    "be able to use multiple transports and use the appropriate one (SSL)" in {
      val r = system.actorOf(Props[Echo], "roghtaar")
      r.path.toString must be === "tcp.ssl.akka://remote-sys@localhost:23457/remote/tcp.ssl.akka/RemotingSpec@localhost:23456/user/roghtaar"
      r ! 42
      expectMsg(10 seconds, 42)
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
