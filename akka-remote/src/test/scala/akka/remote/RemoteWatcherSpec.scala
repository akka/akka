/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import language.postfixOps
import scala.concurrent.duration._
import akka.testkit._
import akka.actor.ActorSystem
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.ExtendedActorSystem
import akka.actor.RootActorPath
import akka.actor.Identify
import akka.actor.ActorIdentity
import akka.actor.PoisonPill
import akka.actor.AddressTerminated
import akka.actor.MinimalActorRef
import akka.actor.Address

object RemoteWatcherSpec {

  class TestActorProxy(testActor: ActorRef) extends Actor {
    def receive = {
      case msg ⇒ testActor forward msg
    }
  }

  class MyActor extends Actor {
    def receive = Actor.emptyBehavior
  }

  case class WrappedAddressTerminated(msg: AddressTerminated)

  // turn off all periodic activity
  val TurnOff = 5.minutes

  def createFailureDetector(): FailureDetectorRegistry[Address] = {
    def createFailureDetector(): FailureDetector =
      new PhiAccrualFailureDetector(
        threshold = 8.0,
        maxSampleSize = 200,
        minStdDeviation = 100.millis,
        acceptableHeartbeatPause = 3.seconds,
        firstHeartbeatEstimate = 1.second)

    new DefaultFailureDetectorRegistry(() ⇒ createFailureDetector())
  }

  object TestRemoteWatcher {
    case class Quarantined(address: Address, uid: Int)
  }

  class TestRemoteWatcher(heartbeatExpectedResponseAfter: FiniteDuration) extends RemoteWatcher(createFailureDetector,
    heartbeatInterval = TurnOff,
    unreachableReaperInterval = TurnOff,
    heartbeatExpectedResponseAfter = heartbeatExpectedResponseAfter,
    numberOfEndHeartbeatRequests = 3) {

    def this() = this(heartbeatExpectedResponseAfter = TurnOff)

    override def quarantine(address: Address, uid: Int): Unit = {
      // don't quarantine in remoting, but publish a testable message
      context.system.eventStream.publish(TestRemoteWatcher.Quarantined(address, uid))
    }

  }

}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class RemoteWatcherSpec extends AkkaSpec(
  """akka {
       # loglevel = DEBUG
       actor.provider = "akka.remote.RemoteActorRefProvider"
       remote.netty.tcp {
         hostname = localhost
         port = 0
       }
     }""") with ImplicitSender {

  import RemoteWatcherSpec._
  import RemoteWatcher._

  override def expectedTestDuration = 2.minutes

  val remoteSystem = ActorSystem("RemoteSystem", system.settings.config)
  val remoteAddress = remoteSystem.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
  def remoteAddressUid = AddressUidExtension(remoteSystem).addressUid

  Seq(system, remoteSystem).foreach(muteDeadLetters("Disassociated.*", "DisassociateUnderlying.*")(_))

  override def afterTermination() {
    remoteSystem.shutdown()
  }

  val heartbeatMsgB = Heartbeat(remoteAddressUid)

  def createRemoteActor(props: Props, name: String): ActorRef = {
    remoteSystem.actorOf(props, name)
    system.actorSelection(RootActorPath(remoteAddress) / "user" / name) ! Identify(name)
    expectMsgType[ActorIdentity].ref.get
  }

  // AddressTerminated is AutoReceiveMessage
  def addressTerminatedSubscriber(fwTo: ActorRef) = new MinimalActorRef {
    override def !(message: Any)(implicit sender: ActorRef = Actor.noSender): Unit = message match {
      case msg: AddressTerminated ⇒ fwTo.tell(WrappedAddressTerminated(msg), sender)
    }
    override val path = system / "testSubscriber" / fwTo.path.name
    override def provider = throw new UnsupportedOperationException("UndefinedUidActorRef does not provide")
  }

  "A RemoteWatcher" must {

    "have correct interaction when watching" in {

      val fd = createFailureDetector()
      val monitorA = system.actorOf(Props[TestRemoteWatcher], "monitor1")
      val monitorB = createRemoteActor(Props(classOf[TestActorProxy], testActor), "monitor1")

      val a1 = system.actorOf(Props[MyActor], "a1")
      val a2 = system.actorOf(Props[MyActor], "a2")
      val b1 = createRemoteActor(Props[MyActor], "b1")
      val b2 = createRemoteActor(Props[MyActor], "b2")

      monitorA ! WatchRemote(b1, a1)
      monitorA ! WatchRemote(b2, a1)
      monitorA ! WatchRemote(b2, a2)
      monitorA ! Stats
      // for each watchee the RemoteWatcher also adds its own watch: 5 = 3 + 2
      // (a1->b1), (a1->b2), (a2->b2)
      expectMsg(Stats.counts(watching = 5, watchingNodes = 1, watchedByNodes = 0))
      expectNoMsg(100 millis)
      monitorA ! HeartbeatTick
      expectMsg(HeartbeatRequest)
      expectNoMsg(100 millis)
      monitorA ! HeartbeatTick
      expectMsg(HeartbeatRequest)
      expectNoMsg(100 millis)
      monitorA.tell(heartbeatMsgB, monitorB)
      monitorA ! HeartbeatTick
      expectNoMsg(100 millis)

      monitorA ! UnwatchRemote(b1, a1)
      // still (a1->b2) and (a2->b2) left
      monitorA ! Stats
      expectMsg(Stats.counts(watching = 3, watchingNodes = 1, watchedByNodes = 0))
      expectNoMsg(100 millis)
      monitorA ! HeartbeatTick
      expectNoMsg(100 millis)

      monitorA ! UnwatchRemote(b2, a2)
      // still (a1->b2) left
      monitorA ! Stats
      expectMsg(Stats.counts(watching = 2, watchingNodes = 1, watchedByNodes = 0))
      expectNoMsg(100 millis)
      monitorA ! HeartbeatTick
      expectNoMsg(100 millis)

      monitorA ! UnwatchRemote(b2, a1)
      // all unwatched
      monitorA ! Stats
      expectMsg(Stats.empty)
      expectNoMsg(100 millis)
      // expecting 3 EndHeartbeatRequest
      monitorA ! HeartbeatTick
      expectMsg(EndHeartbeatRequest)
      expectNoMsg(100 millis)
      monitorA ! HeartbeatTick
      expectMsg(EndHeartbeatRequest)
      expectNoMsg(100 millis)
      monitorA ! HeartbeatTick
      expectMsg(EndHeartbeatRequest)
      expectNoMsg(100 millis)
      monitorA ! HeartbeatTick
      expectNoMsg(100 millis)

      // make sure nothing floods over to next test
      expectNoMsg(2 seconds)
    }

    "have correct interaction when beeing watched" in {

      val monitorA = system.actorOf(Props(classOf[TestActorProxy], testActor), "monitor2")
      val monitorB = createRemoteActor(Props[TestRemoteWatcher], "monitor2")

      val b3 = createRemoteActor(Props[MyActor], "b3")

      // watch
      monitorB.tell(HeartbeatRequest, monitorA)
      monitorB ! Stats
      // HeartbeatRequest adds cross watch to RemoteWatcher peer
      expectMsg(Stats.counts(watching = 1, watchingNodes = 0, watchedByNodes = 1))
      expectNoMsg(100 millis)
      monitorB ! HeartbeatTick
      expectMsg(heartbeatMsgB)
      expectNoMsg(100 millis)
      monitorB ! HeartbeatTick
      expectMsg(heartbeatMsgB)
      expectNoMsg(100 millis)

      // unwatch
      monitorB.tell(EndHeartbeatRequest, monitorA)
      monitorB ! Stats
      // EndHeartbeatRequest should remove the cross watch
      expectMsg(Stats.empty)
      expectNoMsg(100 millis)
      monitorB ! HeartbeatTick
      expectNoMsg(100 millis)

      // start heartbeating again
      monitorB.tell(HeartbeatRequest, monitorA)
      monitorB ! Stats
      expectMsg(Stats.counts(watching = 1, watchingNodes = 0, watchedByNodes = 1))
      expectNoMsg(100 millis)
      monitorB ! HeartbeatTick
      expectMsg(heartbeatMsgB)
      expectNoMsg(100 millis)

      // then kill other side, which should stop the heartbeating
      monitorA ! PoisonPill
      awaitAssert {
        monitorB ! Stats
        expectMsg(Stats.empty)
      }
      monitorB ! HeartbeatTick
      expectNoMsg(500 millis)

      // make sure nothing floods over to next test
      expectNoMsg(2 seconds)
    }

    "generate AddressTerminated when missing heartbeats" in {
      val p = TestProbe()
      val q = TestProbe()
      system.eventStream.subscribe(addressTerminatedSubscriber(p.ref), classOf[AddressTerminated])
      system.eventStream.subscribe(q.ref, classOf[TestRemoteWatcher.Quarantined])

      val monitorA = system.actorOf(Props[TestRemoteWatcher], "monitor4")
      val monitorB = createRemoteActor(Props(classOf[TestActorProxy], testActor), "monitor4")

      val a = system.actorOf(Props[MyActor], "a4")
      val b = createRemoteActor(Props[MyActor], "b4")

      monitorA ! WatchRemote(b, a)

      monitorA ! HeartbeatTick
      expectMsg(HeartbeatRequest)
      monitorA.tell(heartbeatMsgB, monitorB)
      expectNoMsg(1 second)
      monitorA.tell(heartbeatMsgB, monitorB)

      within(10 seconds) {
        awaitAssert {
          monitorA ! HeartbeatTick
          monitorA ! ReapUnreachableTick
          p.expectMsg(1 second, WrappedAddressTerminated(AddressTerminated(b.path.address)))
          q.expectMsg(1 second, TestRemoteWatcher.Quarantined(b.path.address, remoteAddressUid))
        }
      }

      // make sure nothing floods over to next test
      expectNoMsg(2 seconds)
    }

    "generate AddressTerminated when missing first heartbeat" in {
      val p = TestProbe()
      val q = TestProbe()
      system.eventStream.subscribe(addressTerminatedSubscriber(p.ref), classOf[AddressTerminated])
      system.eventStream.subscribe(q.ref, classOf[TestRemoteWatcher.Quarantined])

      val fd = createFailureDetector()
      val heartbeatExpectedResponseAfter = 2.seconds
      val monitorA = system.actorOf(Props(classOf[TestRemoteWatcher], heartbeatExpectedResponseAfter), "monitor5")
      val monitorB = createRemoteActor(Props(classOf[TestActorProxy], testActor), "monitor5")

      val a = system.actorOf(Props[MyActor], "a5")
      val b = createRemoteActor(Props[MyActor], "b5")

      monitorA ! WatchRemote(b, a)

      monitorA ! HeartbeatTick
      expectMsg(HeartbeatRequest)
      // no heartbeats sent

      within(20 seconds) {
        awaitAssert {
          monitorA ! HeartbeatTick
          monitorA ! ReapUnreachableTick
          p.expectMsg(1 second, WrappedAddressTerminated(AddressTerminated(b.path.address)))
          // no quarantine when missing first heartbeat, uid unknown
          q.expectNoMsg(1 second)
        }
      }

      // some more HeartbeatRequest may be sent
      receiveWhile(1.second) {
        case HeartbeatRequest ⇒ // ok
      }

      // make sure nothing floods over to next test
      expectNoMsg(2 seconds)

    }

    "generate AddressTerminated for new watch after broken connection that was re-established and broken again" in {
      val p = TestProbe()
      val q = TestProbe()
      system.eventStream.subscribe(addressTerminatedSubscriber(p.ref), classOf[AddressTerminated])
      system.eventStream.subscribe(q.ref, classOf[TestRemoteWatcher.Quarantined])

      val monitorA = system.actorOf(Props[TestRemoteWatcher], "monitor6")
      val monitorB = createRemoteActor(Props(classOf[TestActorProxy], testActor), "monitor6")

      val a = system.actorOf(Props[MyActor], "a6")
      val b = createRemoteActor(Props[MyActor], "b6")

      monitorA ! WatchRemote(b, a)

      monitorA ! HeartbeatTick
      expectMsg(HeartbeatRequest)
      monitorA.tell(heartbeatMsgB, monitorB)
      expectNoMsg(1 second)
      monitorA.tell(heartbeatMsgB, monitorB)

      within(10 seconds) {
        awaitAssert {
          monitorA ! HeartbeatTick
          monitorA ! ReapUnreachableTick
          p.expectMsg(1 second, WrappedAddressTerminated(AddressTerminated(b.path.address)))
          q.expectMsg(1 second, TestRemoteWatcher.Quarantined(b.path.address, remoteAddressUid))
        }
      }

      // assume that connection comes up again, or remote system is restarted
      val c = createRemoteActor(Props[MyActor], "c6")

      monitorA ! WatchRemote(b, a)

      monitorA ! HeartbeatTick
      expectMsg(HeartbeatRequest)
      monitorA.tell(heartbeatMsgB, monitorB)
      expectNoMsg(1 second)
      monitorA.tell(heartbeatMsgB, monitorB)
      monitorA ! HeartbeatTick
      monitorA ! ReapUnreachableTick
      p.expectNoMsg(1 second)
      monitorA.tell(heartbeatMsgB, monitorB)
      monitorA ! HeartbeatTick
      monitorA ! ReapUnreachableTick
      p.expectNoMsg(1 second)
      q.expectNoMsg(1 second)

      // then stop heartbeating again, should generate new AddressTerminated
      within(10 seconds) {
        awaitAssert {
          monitorA ! HeartbeatTick
          monitorA ! ReapUnreachableTick
          p.expectMsg(1 second, WrappedAddressTerminated(AddressTerminated(b.path.address)))
          q.expectMsg(1 second, TestRemoteWatcher.Quarantined(b.path.address, remoteAddressUid))
        }
      }

      // make sure nothing floods over to next test
      expectNoMsg(2 seconds)
    }

  }

}
