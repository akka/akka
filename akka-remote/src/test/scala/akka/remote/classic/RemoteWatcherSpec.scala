/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.classic

import scala.concurrent.duration._
import scala.language.postfixOps

import scala.annotation.nowarn

import akka.actor._
import akka.remote._
import akka.testkit._

object RemoteWatcherSpec {

  class TestActorProxy(testActor: ActorRef) extends Actor {
    def receive = {
      case msg => testActor.forward(msg)
    }
  }

  class MyActor extends Actor {
    def receive = Actor.emptyBehavior
  }

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

    new DefaultFailureDetectorRegistry(() => createFailureDetector())
  }

  object TestRemoteWatcher {
    final case class AddressTerm(address: Address)
    final case class Quarantined(address: Address, uid: Option[Long])
  }

  class TestRemoteWatcher(heartbeatExpectedResponseAfter: FiniteDuration)
      extends RemoteWatcher(
        createFailureDetector(),
        heartbeatInterval = TurnOff,
        unreachableReaperInterval = TurnOff,
        heartbeatExpectedResponseAfter = heartbeatExpectedResponseAfter) {

    def this() = this(heartbeatExpectedResponseAfter = TurnOff)

    override def publishAddressTerminated(address: Address): Unit =
      // don't publish the real AddressTerminated, but a testable message,
      // that doesn't interfere with the real watch that is going on in the background
      context.system.eventStream.publish(TestRemoteWatcher.AddressTerm(address))

    override def quarantine(address: Address, uid: Option[Long], reason: String, harmless: Boolean): Unit = {
      // don't quarantine in remoting, but publish a testable message
      context.system.eventStream.publish(TestRemoteWatcher.Quarantined(address, uid))
    }

  }

}

@nowarn("msg=deprecated")
class RemoteWatcherSpec extends AkkaSpec("""
     akka {
       loglevel = INFO
       log-dead-letters-during-shutdown = false
       actor.provider = remote
       remote.classic.netty.tcp {
         hostname = localhost
         port = 0
       }
       remote.artery.enabled = off
       remote.use-unsafe-remote-features-outside-cluster = on
     }
     # test is using Java serialization and not priority to rewrite
     akka.actor.allow-java-serialization = on
     akka.actor.warn-about-java-serializer-usage = off
     """) with ImplicitSender {

  import RemoteWatcher._
  import RemoteWatcherSpec._

  override def expectedTestDuration = 2.minutes

  val remoteSystem = ActorSystem("RemoteSystem", system.settings.config)
  val remoteAddress = remoteSystem.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
  @nowarn
  def remoteAddressUid = AddressUidExtension(remoteSystem).addressUid

  Seq(system, remoteSystem).foreach(
    muteDeadLetters(
      akka.remote.transport.AssociationHandle.Disassociated.getClass,
      akka.remote.transport.ActorTransportAdapter.DisassociateUnderlying.getClass)(_))

  override def afterTermination(): Unit = {
    shutdown(remoteSystem)
  }

  val heartbeatRspB = HeartbeatRsp(remoteAddressUid)

  def createRemoteActor(props: Props, name: String): InternalActorRef = {
    remoteSystem.actorOf(props, name)
    system.actorSelection(RootActorPath(remoteAddress) / "user" / name) ! Identify(name)
    expectMsgType[ActorIdentity].ref.get.asInstanceOf[InternalActorRef]
  }

  "A RemoteWatcher" must {

    "have correct interaction when watching" in {
      val monitorA = system.actorOf(Props[TestRemoteWatcher](), "monitor1")
      val monitorB = createRemoteActor(Props(classOf[TestActorProxy], testActor), "monitor1")

      val a1 = system.actorOf(Props[MyActor](), "a1").asInstanceOf[InternalActorRef]
      val a2 = system.actorOf(Props[MyActor](), "a2").asInstanceOf[InternalActorRef]
      val b1 = createRemoteActor(Props[MyActor](), "b1")
      val b2 = createRemoteActor(Props[MyActor](), "b2")

      monitorA ! WatchRemote(b1, a1)
      monitorA ! WatchRemote(b2, a1)
      monitorA ! WatchRemote(b2, a2)
      monitorA ! Stats
      // (a1->b1), (a1->b2), (a2->b2)
      expectMsg(Stats.counts(watching = 3, watchingNodes = 1))
      expectNoMessage(100 millis)
      monitorA ! HeartbeatTick
      expectMsg(Heartbeat)
      expectNoMessage(100 millis)
      monitorA ! HeartbeatTick
      expectMsg(Heartbeat)
      expectNoMessage(100 millis)
      monitorA.tell(heartbeatRspB, monitorB)
      monitorA ! HeartbeatTick
      expectMsg(Heartbeat)
      expectNoMessage(100 millis)

      monitorA ! UnwatchRemote(b1, a1)
      // still (a1->b2) and (a2->b2) left
      monitorA ! Stats
      expectMsg(Stats.counts(watching = 2, watchingNodes = 1))
      expectNoMessage(100 millis)
      monitorA ! HeartbeatTick
      expectMsg(Heartbeat)
      expectNoMessage(100 millis)

      monitorA ! UnwatchRemote(b2, a2)
      // still (a1->b2) left
      monitorA ! Stats
      expectMsg(Stats.counts(watching = 1, watchingNodes = 1))
      expectNoMessage(100 millis)
      monitorA ! HeartbeatTick
      expectMsg(Heartbeat)
      expectNoMessage(100 millis)

      monitorA ! UnwatchRemote(b2, a1)
      // all unwatched
      monitorA ! Stats
      expectMsg(Stats.empty)
      expectNoMessage(100 millis)
      monitorA ! HeartbeatTick
      expectNoMessage(100 millis)
      monitorA ! HeartbeatTick
      expectNoMessage(100 millis)

      // make sure nothing floods over to next test
      expectNoMessage(2 seconds)
    }

    "generate AddressTerminated when missing heartbeats" taggedAs LongRunningTest in {
      val p = TestProbe()
      val q = TestProbe()
      system.eventStream.subscribe(p.ref, classOf[TestRemoteWatcher.AddressTerm])
      system.eventStream.subscribe(q.ref, classOf[TestRemoteWatcher.Quarantined])

      val monitorA = system.actorOf(Props[TestRemoteWatcher](), "monitor4")
      val monitorB = createRemoteActor(Props(classOf[TestActorProxy], testActor), "monitor4")

      val a = system.actorOf(Props[MyActor](), "a4").asInstanceOf[InternalActorRef]
      val b = createRemoteActor(Props[MyActor](), "b4")

      monitorA ! WatchRemote(b, a)

      monitorA ! HeartbeatTick
      expectMsg(Heartbeat)
      monitorA.tell(heartbeatRspB, monitorB)
      expectNoMessage(1 second)
      monitorA ! HeartbeatTick
      expectMsg(Heartbeat)
      monitorA.tell(heartbeatRspB, monitorB)

      within(10 seconds) {
        awaitAssert {
          monitorA ! HeartbeatTick
          expectMsg(Heartbeat)
          // but no HeartbeatRsp
          monitorA ! ReapUnreachableTick
          p.expectMsg(1 second, TestRemoteWatcher.AddressTerm(b.path.address))
          q.expectMsg(1 second, TestRemoteWatcher.Quarantined(b.path.address, Some(remoteAddressUid)))
        }
      }

      // make sure nothing floods over to next test
      expectNoMessage(2 seconds)
    }

    "generate AddressTerminated when missing first heartbeat" taggedAs LongRunningTest in {
      val p = TestProbe()
      val q = TestProbe()
      system.eventStream.subscribe(p.ref, classOf[TestRemoteWatcher.AddressTerm])
      system.eventStream.subscribe(q.ref, classOf[TestRemoteWatcher.Quarantined])

      val heartbeatExpectedResponseAfter = 2.seconds
      val monitorA = system.actorOf(Props(classOf[TestRemoteWatcher], heartbeatExpectedResponseAfter), "monitor5")
      createRemoteActor(Props(classOf[TestActorProxy], testActor), "monitor5")

      val a = system.actorOf(Props[MyActor](), "a5").asInstanceOf[InternalActorRef]
      val b = createRemoteActor(Props[MyActor](), "b5")

      monitorA ! WatchRemote(b, a)

      monitorA ! HeartbeatTick
      expectMsg(Heartbeat)
      // no HeartbeatRsp sent

      within(20 seconds) {
        awaitAssert {
          monitorA ! HeartbeatTick
          expectMsg(Heartbeat)
          // but no HeartbeatRsp
          monitorA ! ReapUnreachableTick
          p.expectMsg(1 second, TestRemoteWatcher.AddressTerm(b.path.address))
          // no real quarantine when missing first heartbeat, uid unknown
          q.expectMsg(1 second, TestRemoteWatcher.Quarantined(b.path.address, None))
        }
      }

      // make sure nothing floods over to next test
      expectNoMessage(2 seconds)
    }

    "generate AddressTerminated for new watch after broken connection that was re-established and broken again" taggedAs LongRunningTest in {
      val p = TestProbe()
      val q = TestProbe()
      system.eventStream.subscribe(p.ref, classOf[TestRemoteWatcher.AddressTerm])
      system.eventStream.subscribe(q.ref, classOf[TestRemoteWatcher.Quarantined])

      val monitorA = system.actorOf(Props[TestRemoteWatcher](), "monitor6")
      val monitorB = createRemoteActor(Props(classOf[TestActorProxy], testActor), "monitor6")

      val a = system.actorOf(Props[MyActor](), "a6").asInstanceOf[InternalActorRef]
      val b = createRemoteActor(Props[MyActor](), "b6")

      monitorA ! WatchRemote(b, a)

      monitorA ! HeartbeatTick
      expectMsg(Heartbeat)
      monitorA.tell(heartbeatRspB, monitorB)
      expectNoMessage(1 second)
      monitorA ! HeartbeatTick
      expectMsg(Heartbeat)
      monitorA.tell(heartbeatRspB, monitorB)

      within(10 seconds) {
        awaitAssert {
          monitorA ! HeartbeatTick
          expectMsg(Heartbeat)
          // but no HeartbeatRsp
          monitorA ! ReapUnreachableTick
          p.expectMsg(1 second, TestRemoteWatcher.AddressTerm(b.path.address))
          q.expectMsg(1 second, TestRemoteWatcher.Quarantined(b.path.address, Some(remoteAddressUid)))
        }
      }

      // real AddressTerminated would trigger Terminated for b6, simulate that here
      remoteSystem.stop(b)
      awaitAssert {
        monitorA ! Stats
        expectMsg(Stats.empty)
      }
      expectNoMessage(2 seconds)

      // assume that connection comes up again, or remote system is restarted
      val c = createRemoteActor(Props[MyActor](), "c6")

      monitorA ! WatchRemote(c, a)

      monitorA ! HeartbeatTick
      expectMsg(Heartbeat)
      monitorA.tell(heartbeatRspB, monitorB)
      expectNoMessage(1 second)
      monitorA ! HeartbeatTick
      expectMsg(Heartbeat)
      monitorA.tell(heartbeatRspB, monitorB)
      monitorA ! HeartbeatTick
      expectMsg(Heartbeat)
      monitorA ! ReapUnreachableTick
      p.expectNoMessage(1 second)
      monitorA ! HeartbeatTick
      expectMsg(Heartbeat)
      monitorA.tell(heartbeatRspB, monitorB)
      monitorA ! HeartbeatTick
      expectMsg(Heartbeat)
      monitorA ! ReapUnreachableTick
      p.expectNoMessage(1 second)
      q.expectNoMessage(1 second)

      // then stop heartbeating again, should generate new AddressTerminated
      within(10 seconds) {
        awaitAssert {
          monitorA ! HeartbeatTick
          expectMsg(Heartbeat)
          // but no HeartbeatRsp
          monitorA ! ReapUnreachableTick
          p.expectMsg(1 second, TestRemoteWatcher.AddressTerm(c.path.address))
          q.expectMsg(1 second, TestRemoteWatcher.Quarantined(c.path.address, Some(remoteAddressUid)))
        }
      }

      // make sure nothing floods over to next test
      expectNoMessage(2 seconds)
    }

  }

}
