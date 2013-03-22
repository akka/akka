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
  class Echo1 extends Actor {
    var target: ActorRef = context.system.deadLetters

    def receive = {
      case (p: Props, n: String) ⇒ sender ! context.actorOf(Props[Echo1], n)
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

  class Echo2 extends Actor {
    def receive = {
      case "ping" ⇒ sender ! (("pong", sender))
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
    }

    common-netty-settings {
      port = 0
      hostname = "localhost"
      server-socket-worker-pool.pool-size-max = 2
      client-socket-worker-pool.pool-size-max = 2
    }

    akka {
      actor.provider = "akka.remote.RemoteActorRefProvider"

      remote {
        transport = "akka.remote.Remoting"

        retry-latch-closed-for = 1 s
        log-remote-lifecycle-events = on

        enabled-transports = [
          "akka.remote.test",
          "akka.remote.netty.tcp",
          "akka.remote.netty.udp",
          "akka.remote.netty.ssl"
        ]

        writer-dispatcher {
          executor = "fork-join-executor"
          fork-join-executor {
            parallelism-min = 2
            parallelism-max = 2
          }
        }

        netty.tcp = ${common-netty-settings}
        netty.udp = ${common-netty-settings}
        netty.ssl = ${common-netty-settings}
        netty.ssl.security = ${common-ssl-settings}

        test {
          transport-class = "akka.remote.transport.TestTransport"
          applied-adapters = []
          registry-key = aX33k0jWKg
          local-address = "test://RemotingSpec@localhost:12345"
          maximum-payload-bytes = 32000 bytes
          scheme-identifier = test
        }
      }

      actor.deployment {
        /blub.remote = "akka.test://remote-sys@localhost:12346"
        /looker/child.remote = "akka.test://remote-sys@localhost:12346"
        /looker/child/grandchild.remote = "akka.test://RemotingSpec@localhost:12345"
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
  val otherSystem = ActorSystem("remote-sys", conf)

  for (
    (name, proto) ← Seq(
      "/gonk" -> "tcp",
      "/zagzag" -> "udp",
      "/roghtaar" -> "ssl.tcp")
  ) deploy(system, Deploy(name, scope = RemoteScope(addr(otherSystem, proto))))

  def addr(sys: ActorSystem, proto: String) =
    sys.asInstanceOf[ExtendedActorSystem].provider.getExternalAddressFor(Address(s"akka.$proto", "", "", 0)).get
  def port(sys: ActorSystem, proto: String) = addr(sys, proto).port.get
  def deploy(sys: ActorSystem, d: Deploy) {
    sys.asInstanceOf[ExtendedActorSystem].provider.asInstanceOf[RemoteActorRefProvider].deployer.deploy(d)
  }

  val remote = otherSystem.actorOf(Props[Echo2], "echo")

  val here = system.actorFor("akka.test://remote-sys@localhost:12346/user/echo")

  override def afterTermination() {
    otherSystem.shutdown()
    AssociationRegistry.clear()
  }

  "Remoting" must {

    "support remote look-ups" in {
      here ! "ping"
      expectMsg(("pong", testActor))
    }

    "send error message for wrong address" in {
      filterEvents(EventFilter.error(start = "Association", occurrences = 6),
        EventFilter.warning(pattern = ".*dead letter.*echo.*", occurrences = 1)) {
          system.actorFor("akka.test://nonexistingsystem@localhost:12346/user/echo") ! "ping"
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
        system.actorFor("akka.test://remote-sys@localhost:12346/does/not/exist") ! "buh"
      }(otherSystem)
    }

    "not be exhausted by sending to broken connections" in {
      val tcpOnlyConfig = ConfigFactory.parseString("""akka.remote.enabled-transports = ["akka.remote.netty.tcp"]""").
        withFallback(otherSystem.settings.config)
      val moreSystems = Vector.fill(5)(ActorSystem(otherSystem.name, tcpOnlyConfig))
      moreSystems foreach (_.actorOf(Props[Echo2], name = "echo"))
      val moreRefs = moreSystems map (sys ⇒ system.actorFor(RootActorPath(addr(sys, "tcp")) / "user" / "echo"))
      val aliveEcho = system.actorFor(RootActorPath(addr(otherSystem, "tcp")) / "user" / "echo")
      val n = 100

      // first everything is up and running
      1 to n foreach { x ⇒
        aliveEcho ! "ping"
        moreRefs(x % moreSystems.size) ! "ping"
      }

      within(5.seconds) {
        receiveN(n * 2) foreach { reply ⇒ reply must be(("pong", testActor)) }
      }

      // then we shutdown all but one system to simulate broken connections
      moreSystems foreach { sys ⇒
        sys.shutdown()
        sys.awaitTermination(5.seconds.dilated)
        sys.isTerminated must be(true)
      }

      1 to n foreach { x ⇒
        aliveEcho ! "ping"
        moreRefs(x % moreSystems.size) ! "ping"
      }

      // ping messages to aliveEcho should go through even though we use many different broken connections
      within(5.seconds) {
        receiveN(n) foreach { reply ⇒ reply must be(("pong", testActor)) }
      }
    }

    "create and supervise children on remote node" in {
      val r = system.actorOf(Props[Echo1], "blub")
      r.path.toString must be === "akka.test://remote-sys@localhost:12346/remote/akka.test/RemotingSpec@localhost:12345/user/blub"
      r ! 42
      expectMsg(42)
      EventFilter[Exception]("crash", occurrences = 1).intercept {
        r ! new Exception("crash")
      }
      expectMsg("preRestart")
      r ! 42
      expectMsg(42)
      system.stop(r)
      expectMsg("postStop")
    }

    "not send to remote re-created actor with same name" in {
      val echo = otherSystem.actorOf(Props[Echo1], "otherEcho1")
      echo ! 71
      expectMsg(71)
      echo ! PoisonPill
      expectMsg("postStop")
      echo ! 72
      expectNoMsg(1.second)

      val echo2 = otherSystem.actorOf(Props[Echo1], "otherEcho1")
      echo2 ! 73
      expectMsg(73)
      // msg to old ActorRef (different uid) should not get through
      echo2.path.uid must not be (echo.path.uid)
      echo ! 74
      expectNoMsg(1.second)

      otherSystem.actorFor("/user/otherEcho1") ! 75
      expectMsg(75)

      system.actorFor("akka.test://remote-sys@localhost:12346/user/otherEcho1") ! 76
      expectMsg(76)
    }

    "look-up actors across node boundaries" in {
      val l = system.actorOf(Props(new Actor {
        def receive = {
          case (p: Props, n: String) ⇒ sender ! context.actorOf(p, n)
          case s: String             ⇒ sender ! context.actorFor(s)
        }
      }), "looker")
      // child is configured to be deployed on remote-sys (otherSystem)
      l ! (Props[Echo1], "child")
      val child = expectMsgType[ActorRef]
      // grandchild is configured to be deployed on RemotingSpec (system)
      child ! (Props[Echo1], "grandchild")
      val grandchild = expectMsgType[ActorRef]
      grandchild.asInstanceOf[ActorRefScope].isLocal must be(true)
      grandchild ! 43
      expectMsg(43)
      val myref = system.actorFor(system / "looker" / "child" / "grandchild")
      myref.isInstanceOf[RemoteActorRef] must be(true)
      myref ! 44
      expectMsg(44)
      lastSender must be(grandchild)
      lastSender must be theSameInstanceAs grandchild
      child.asInstanceOf[RemoteActorRef].getParent must be(l)
      system.actorFor("/user/looker/child") must be theSameInstanceAs child
      Await.result(l ? "child/..", timeout.duration).asInstanceOf[AnyRef] must be theSameInstanceAs l
      Await.result(system.actorFor(system / "looker" / "child") ? "..", timeout.duration).asInstanceOf[AnyRef] must be theSameInstanceAs l

      watch(child)
      child ! PoisonPill
      expectMsg("postStop")
      expectMsgType[Terminated].actor must be === child
      l ! (Props[Echo1], "child")
      val child2 = expectMsgType[ActorRef]
      child2 ! 45
      expectMsg(45)
      // msg to old ActorRef (different uid) should not get through
      child2.path.uid must not be (child.path.uid)
      child ! 46
      expectNoMsg(1.second)
      system.actorFor(system / "looker" / "child") ! 47
      expectMsg(47)

    }

    "not fail ask across node boundaries" in {
      import system.dispatcher
      val f = for (_ ← 1 to 1000) yield here ? "ping" mapTo manifest[(String, ActorRef)]
      Await.result(Future.sequence(f), remaining).map(_._1).toSet must be(Set("pong"))
    }

    "be able to use multiple transports and use the appropriate one (TCP)" in {
      val r = system.actorOf(Props[Echo1], "gonk")
      r.path.toString must be ===
        s"akka.tcp://remote-sys@localhost:${port(otherSystem, "tcp")}/remote/akka.tcp/RemotingSpec@localhost:${port(system, "tcp")}/user/gonk"
      r ! 42
      expectMsg(42)
      EventFilter[Exception]("crash", occurrences = 1).intercept {
        r ! new Exception("crash")
      }
      expectMsg("preRestart")
      r ! 42
      expectMsg(42)
      system.stop(r)
      expectMsg("postStop")
    }

    "be able to use multiple transports and use the appropriate one (UDP)" in {
      val r = system.actorOf(Props[Echo1], "zagzag")
      r.path.toString must be ===
        s"akka.udp://remote-sys@localhost:${port(otherSystem, "udp")}/remote/akka.udp/RemotingSpec@localhost:${port(system, "udp")}/user/zagzag"
      r ! 42
      expectMsg(10.seconds, 42)
      EventFilter[Exception]("crash", occurrences = 1).intercept {
        r ! new Exception("crash")
      }
      expectMsg("preRestart")
      r ! 42
      expectMsg(42)
      system.stop(r)
      expectMsg("postStop")
    }

    "be able to use multiple transports and use the appropriate one (SSL)" in {
      val r = system.actorOf(Props[Echo1], "roghtaar")
      r.path.toString must be ===
        s"akka.ssl.tcp://remote-sys@localhost:${port(otherSystem, "ssl.tcp")}/remote/akka.ssl.tcp/RemotingSpec@localhost:${port(system, "ssl.tcp")}/user/roghtaar"
      r ! 42
      expectMsg(10.seconds, 42)
      EventFilter[Exception]("crash", occurrences = 1).intercept {
        r ! new Exception("crash")
      }
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
    otherSystem.eventStream.publish(TestEvent.Mute(
      EventFilter[EndpointException](),
      EventFilter.error(start = "AssociationError"),
      EventFilter.warning(pattern = "received dead letter.*(InboundPayload|Disassociate|HandleListener)")))
  }

}
