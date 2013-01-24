/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
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

      remote.retry-latch-closed-for = 1 s
      remote.log-remote-lifecycle-events = on

      remote.enabled-transports = [
        "akka.remote.test",
        "akka.remote.netty.tcp",
        "akka.remote.netty.udp",
        "akka.remote.netty.ssl"
      ]

      remote.netty.tcp.port = 0
      remote.netty.tcp.hostname = "localhost"
      remote.netty.udp.port = 0
      remote.netty.udp.hostname = "localhost"
      remote.netty.ssl.port = 0
      remote.netty.ssl.hostname = "localhost"
      remote.netty.ssl.ssl = ${common-ssl-settings}

      remote.test {
          transport-class = "akka.remote.transport.TestTransport"
          applied-adapters = []
          registry-key = aX33k0jWKg
          local-address = "test://RemotingSpec@localhost:12345"
          maximum-payload-bytes = 32000 bytes
          scheme-identifier = test
      }

      actor.deployment {
        /blub.remote = "test.akka://remote-sys@localhost:12346"
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
      akka.remote {
        test.local-address = "test://remote-sys@localhost:12346"
      }
    """).withFallback(system.settings.config).resolve()
  val other = ActorSystem("remote-sys", conf)

  for (
    (name, proto) ← Seq(
      "/gonk" -> "tcp",
      "/zagzag" -> "udp",
      "/roghtaar" -> "tcp.ssl")
  ) deploy(system, Deploy(name, scope = RemoteScope(addr(other, proto))))

  def addr(sys: ActorSystem, proto: String) =
    sys.asInstanceOf[ExtendedActorSystem].provider.getExternalAddressFor(Address(s"$proto.akka", "", "", 0)).get
  def port(sys: ActorSystem, proto: String) = addr(sys, proto).port.get
  def deploy(sys: ActorSystem, d: Deploy) {
    sys.asInstanceOf[ExtendedActorSystem].provider.asInstanceOf[RemoteActorRefProvider].deployer.deploy(d)
  }

  val remote = other.actorOf(Props(new Actor {
    def receive = {
      case "ping" ⇒ sender ! (("pong", sender))
    }
  }), "echo")

  val here = system.actorFor("test.akka://remote-sys@localhost:12346/user/echo")

  override def afterTermination() {
    other.shutdown()
    AssociationRegistry.clear()
  }

  "Remoting" must {

    "support remote look-ups" in {
      here ! "ping"
      expectMsg(("pong", testActor))
    }

    "send error message for wrong address" in {
      filterEvents(EventFilter[EndpointException](occurrences = 6), EventFilter.error(start = "Association", occurrences = 6)) {
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
      r.path.toString must be ===
        s"tcp.akka://remote-sys@localhost:${port(other, "tcp")}/remote/tcp.akka/RemotingSpec@localhost:${port(system, "tcp")}/user/gonk"
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
      r.path.toString must be ===
        s"udp.akka://remote-sys@localhost:${port(other, "udp")}/remote/udp.akka/RemotingSpec@localhost:${port(system, "udp")}/user/zagzag"
      r ! 42
      expectMsg(10.seconds, 42)
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
      r.path.toString must be ===
        s"tcp.ssl.akka://remote-sys@localhost:${port(other, "tcp.ssl")}/remote/tcp.ssl.akka/RemotingSpec@localhost:${port(system, "tcp.ssl")}/user/roghtaar"
      r ! 42
      expectMsg(10.seconds, 42)
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

  override def beforeTermination() {
    system.eventStream.publish(TestEvent.Mute(
      EventFilter.warning(pattern = "received dead letter.*(InboundPayload|Disassociate)")))
    other.eventStream.publish(TestEvent.Mute(
      EventFilter[EndpointException](),
      EventFilter.error(start = "AssociationError"),
      EventFilter.warning(pattern = "received dead letter.*(InboundPayload|Disassociate|HandleListener)")))
  }

}
