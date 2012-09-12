/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import akka.testkit.AkkaSpec
import akka.testkit.ImplicitSender
import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import akka.actor.ExtendedActorSystem
import akka.actor.RootActorPath
import akka.testkit.EventFilter
import akka.testkit.TestEvent
import akka.actor.Props
import akka.actor.Actor
import akka.event.Logging
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import akka.actor.Terminated
import scala.concurrent.duration._
import akka.actor.PoisonPill

@RunWith(classOf[JUnitRunner])
class UntrustedSpec extends AkkaSpec("""
akka.actor.provider = akka.remote.RemoteActorRefProvider
akka.remote.untrusted-mode = on
akka.remote.netty.port = 0
akka.remote.log-remote-lifecycle-events = off
akka.loglevel = DEBUG
""") with ImplicitSender {

  val other = ActorSystem("UntrustedSpec-client", ConfigFactory.parseString("""
      akka.actor.provider = akka.remote.RemoteActorRefProvider
      akka.remote.netty.port = 0
      """))
  val addr = system.asInstanceOf[ExtendedActorSystem].provider.asInstanceOf[RemoteActorRefProvider].transport.addresses.head
  val target1 = other.actorFor(RootActorPath(addr) / "remote")
  val target2 = other.actorFor(RootActorPath(addr) / testActor.path.elements)

  // need to enable debug log-level without actually printing those messages
  system.eventStream.publish(TestEvent.Mute(EventFilter.debug()))

  // but instead install our own listener
  system.eventStream.subscribe(system.actorOf(Props(new Actor {
    import Logging._
    def receive = {
      case d @ Debug(_, _, msg: String) if msg contains "dropping" ⇒ testActor ! d
      case _ ⇒
    }
  }), "debugSniffer"), classOf[Logging.Debug])

  "UntrustedMode" must {

    "discard harmful messages to /remote" in {
      target1 ! "hello"
      expectMsgType[Logging.Debug]
    }

    "discard harmful messages to testActor" in {
      target2 ! Terminated(target1)(existenceConfirmed = true, addressTerminated = false)
      expectMsgType[Logging.Debug]
      target2 ! PoisonPill
      expectMsgType[Logging.Debug]
      other.stop(target2)
      expectMsgType[Logging.Debug]
      target2 ! "blech"
      expectMsg("blech")
    }

    "discard watch messages" in {
      other.actorOf(Props(new Actor {
        context.watch(target2)
        def receive = {
          case x ⇒ testActor forward x
        }
      }))
      within(1.second) {
        expectMsgType[Logging.Debug]
        expectNoMsg
      }
    }

  }

}