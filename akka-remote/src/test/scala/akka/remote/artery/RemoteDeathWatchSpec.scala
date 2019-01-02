/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery

import akka.testkit._
import akka.actor._
import com.typesafe.config.ConfigFactory
import akka.actor.RootActorPath
import scala.concurrent.duration._
import akka.testkit.SocketUtil
import akka.remote.QuarantinedEvent
import akka.remote.RARP

object RemoteDeathWatchSpec {
  val otherPort = SocketUtil.temporaryLocalPort(udp = true)

  val config = ConfigFactory.parseString(s"""
    akka {
        actor {
            provider = remote
            deployment {
                /watchers.remote = "akka://other@localhost:$otherPort"
            }
        }
        test.filter-leeway = 10s
        remote.watch-failure-detector.acceptable-heartbeat-pause = 2s

        # reduce handshake timeout for quicker test of unknownhost, but
        # must still be longer than failure detection
        remote.artery.advanced {
          handshake-timeout = 10 s
          image-liveness-timeout = 9 seconds
        }
    }
    """).withFallback(ArterySpecSupport.defaultConfig)
}

class RemoteDeathWatchSpec extends ArteryMultiNodeSpec(RemoteDeathWatchSpec.config) with ImplicitSender with DefaultTimeout with DeathWatchSpec {
  import RemoteDeathWatchSpec._

  system.eventStream.publish(TestEvent.Mute(
    EventFilter[io.aeron.exceptions.RegistrationException]()))

  val other = newRemoteSystem(name = Some("other"), extraConfig = Some(s"akka.remote.artery.canonical.port=$otherPort"))

  override def expectedTestDuration: FiniteDuration = 120.seconds

  "receive Terminated when system of de-serialized ActorRef is not running" in {
    val probe = TestProbe()
    system.eventStream.subscribe(probe.ref, classOf[QuarantinedEvent])
    val rarp = RARP(system).provider
    // pick an unused port
    val port = SocketUtil.temporaryLocalPort(udp = true)
    // simulate de-serialized ActorRef
    val ref = rarp.resolveActorRef(s"akka://OtherSystem@localhost:$port/user/foo/bar#1752527294")

    // we don't expect real quarantine when the UID is unknown, i.e. QuarantinedEvent is not published
    EventFilter.warning(pattern = "Quarantine of .* ignored because unknown UID", occurrences = 1).intercept {
      EventFilter.warning(start = "Detected unreachable", occurrences = 1).intercept {

        system.actorOf(Props(new Actor {
          context.watch(ref)

          def receive = {
            case Terminated(r) ⇒ testActor ! r
          }
        }).withDeploy(Deploy.local))

        expectMsg(10.seconds, ref)
      }
    }
  }

  "receive Terminated when watched node is unknown host" in {
    val path = RootActorPath(Address("akka", system.name, "unknownhost", 2552)) / "user" / "subject"
    system.actorOf(Props(new Actor {
      context.watch(context.actorFor(path))
      def receive = {
        case t: Terminated ⇒ testActor ! t.actor.path
      }
    }).withDeploy(Deploy.local), name = "observer2")

    expectMsg(60.seconds, path)
  }

  "receive ActorIdentity(None) when identified node is unknown host" in {
    // TODO There is a timing difference between Aeron and TCP. AeronSink will throw exception
    // immediately in constructor from aeron.addPublication when UnknownHostException. That will trigger
    // this immediately. With TCP it will trigger after handshake timeout. Can we see the UnknownHostException
    // reason somehow and fail the stream immediately for that case?
    val path = RootActorPath(Address("akka", system.name, "unknownhost2", 2552)) / "user" / "subject"
    system.actorSelection(path) ! Identify(path)
    expectMsg(60.seconds, ActorIdentity(path, None))
  }

}
