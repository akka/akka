/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.actor.Actor
import akka.actor.ActorIdentity
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Deploy
import akka.actor.ExtendedActorSystem
import akka.actor.Identify
import akka.actor.PoisonPill
import akka.actor.Props
import akka.actor.RootActorPath
import akka.actor.Terminated
import akka.testkit.AkkaSpec
import akka.testkit.ImplicitSender
import akka.testkit.TestProbe
import akka.actor.ActorSelection
import akka.testkit.TestEvent
import akka.event.Logging
import akka.testkit.EventFilter

object UntrustedSpec {
  final case class IdentifyReq(path: String)
  final case class StopChild(name: String)

  class Receptionist(testActor: ActorRef) extends Actor {
    context.actorOf(Props(classOf[Child], testActor), "child1")
    context.actorOf(Props(classOf[Child], testActor), "child2")
    context.actorOf(Props(classOf[FakeUser], testActor), "user")

    def receive = {
      case IdentifyReq(path) => context.actorSelection(path).tell(Identify(None), sender())
      case StopChild(name)   => context.child(name).foreach(context.stop)
      case msg               => testActor.forward(msg)
    }
  }

  class Child(testActor: ActorRef) extends Actor {
    override def postStop(): Unit = {
      testActor ! s"${self.path.name} stopped"
    }
    def receive = {
      case msg => testActor.forward(msg)
    }
  }

  class FakeUser(testActor: ActorRef) extends Actor {
    context.actorOf(Props(classOf[Child], testActor), "receptionist")
    def receive = {
      case msg => testActor.forward(msg)
    }
  }

}

class UntrustedSpec extends AkkaSpec("""
akka.actor.provider = remote
akka.remote.untrusted-mode = on
akka.remote.trusted-selection-paths = ["/user/receptionist", ]    
akka.remote.netty.tcp.port = 0
akka.loglevel = DEBUG # test verifies debug
""") with ImplicitSender {

  import UntrustedSpec._

  val client = ActorSystem(
    "UntrustedSpec-client",
    ConfigFactory.parseString("""
      akka.actor.provider = remote
      akka.remote.netty.tcp.port = 0
  """))
  val address = system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress

  val receptionist = system.actorOf(Props(classOf[Receptionist], testActor), "receptionist")

  lazy val remoteDaemon = {
    {
      val p = TestProbe()(client)
      client.actorSelection(RootActorPath(address) / receptionist.path.elements).tell(IdentifyReq("/remote"), p.ref)
      p.expectMsgType[ActorIdentity].ref.get
    }
  }

  lazy val target2 = {
    val p = TestProbe()(client)
    client.actorSelection(RootActorPath(address) / receptionist.path.elements).tell(IdentifyReq("child2"), p.ref)
    p.expectMsgType[ActorIdentity].ref.get
  }

  override def afterTermination(): Unit = {
    shutdown(client)
  }

  // need to enable debug log-level without actually printing those messages
  system.eventStream.publish(TestEvent.Mute(EventFilter.debug()))

  "UntrustedMode" must {

    "allow actor selection to configured white list" in {
      val sel = client.actorSelection(RootActorPath(address) / receptionist.path.elements)
      sel ! "hello"
      expectMsg("hello")
    }

    "discard harmful messages to /remote" in {
      val logProbe = TestProbe()
      // but instead install our own listener
      system.eventStream.subscribe(system.actorOf(Props(new Actor {
        import Logging._
        def receive = {
          case d @ Debug(_, _, msg: String) if msg contains "dropping" => logProbe.ref ! d
          case _                                                       =>
        }
      }).withDeploy(Deploy.local), "debugSniffer"), classOf[Logging.Debug])

      remoteDaemon ! "hello"
      logProbe.expectMsgType[Logging.Debug]
    }

    "discard harmful messages to testActor" in {
      target2 ! Terminated(remoteDaemon)(existenceConfirmed = true, addressTerminated = false)
      target2 ! PoisonPill
      client.stop(target2)
      target2 ! "blech"
      expectMsg("blech")
    }

    "discard watch messages" in {
      client.actorOf(Props(new Actor {
        context.watch(target2)
        def receive = {
          case x => testActor.forward(x)
        }
      }).withDeploy(Deploy.local))
      receptionist ! StopChild("child2")
      expectMsg("child2 stopped")
      // no Terminated msg, since watch was discarded
      expectNoMessage(1.second)
    }

    "discard actor selection" in {
      val sel = client.actorSelection(RootActorPath(address) / testActor.path.elements)
      sel ! "hello"
      expectNoMessage(1.second)
    }

    "discard actor selection with non root anchor" in {
      val p = TestProbe()(client)
      client.actorSelection(RootActorPath(address) / receptionist.path.elements).tell(Identify(None), p.ref)
      val clientReceptionistRef = p.expectMsgType[ActorIdentity].ref.get

      val sel = ActorSelection(clientReceptionistRef, receptionist.path.toStringWithoutAddress)
      sel ! "hello"
      expectNoMessage(1.second)
    }

    "discard actor selection to child of matching white list" in {
      val sel = client.actorSelection(RootActorPath(address) / receptionist.path.elements / "child1")
      sel ! "hello"
      expectNoMessage(1.second)
    }

    "discard actor selection with wildcard" in {
      val sel = client.actorSelection(RootActorPath(address) / receptionist.path.elements / "*")
      sel ! "hello"
      expectNoMessage(1.second)
    }

    "discard actor selection containing harmful message" in {
      val sel = client.actorSelection(RootActorPath(address) / receptionist.path.elements)
      sel ! PoisonPill
      expectNoMessage(1.second)
    }

  }

}
