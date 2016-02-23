/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.persistence

import akka.actor._
import akka.persistence.JournalProtocol.ReplayMessages
import akka.testkit._
import com.typesafe.config.Config

import scala.concurrent.duration._

object PersistentViewSpec {

  private class TestPersistentActor(name: String, probe: ActorRef) extends NamedPersistentActor(name) {
    def receiveCommand = {
      case msg ⇒ persist(msg) { m ⇒ probe ! s"${m}-${lastSequenceNr}" }
    }

    override def receiveRecover: Receive = {
      case _ ⇒ // do nothing...
    }
  }

  private class TestPersistentView(name: String, probe: ActorRef, interval: FiniteDuration, var failAt: Option[String]) extends PersistentView {
    def this(name: String, probe: ActorRef, interval: FiniteDuration) =
      this(name, probe, interval, None)

    def this(name: String, probe: ActorRef) =
      this(name, probe, 100.milliseconds)

    override def autoUpdateInterval: FiniteDuration = interval.dilated(context.system)
    override val persistenceId: String = name
    override val viewId: String = name + "-view"

    var last: String = _

    def receive = {
      case "get" ⇒
        probe ! last
      case "boom" ⇒
        throw new TestException("boom")

      case payload if isPersistent && shouldFailOn(payload) ⇒
        throw new TestException("boom")

      case payload if isPersistent ⇒
        last = s"replicated-${payload}-${lastSequenceNr}"
        probe ! last

    }

    override def postRestart(reason: Throwable): Unit = {
      super.postRestart(reason)
      failAt = None
    }

    def shouldFailOn(m: Any): Boolean =
      failAt.foldLeft(false) { (a, f) ⇒ a || (m == f) }
  }

  private class PassiveTestPersistentView(name: String, probe: ActorRef, var failAt: Option[String]) extends PersistentView {
    override val persistenceId: String = name
    override val viewId: String = name + "-view"

    override def autoUpdate: Boolean = false
    override def autoUpdateReplayMax: Long = 0L // no message replay during initial recovery

    var last: String = _

    def receive = {
      case "get" ⇒
        probe ! last
      case payload if isPersistent && shouldFailOn(payload) ⇒
        throw new TestException("boom")
      case payload ⇒
        last = s"replicated-${payload}-${lastSequenceNr}"
    }

    override def postRestart(reason: Throwable): Unit = {
      super.postRestart(reason)
      failAt = None
    }

    def shouldFailOn(m: Any): Boolean =
      failAt.foldLeft(false) { (a, f) ⇒ a || (m == f) }

  }

  private class ActiveTestPersistentView(name: String, probe: ActorRef) extends PersistentView {
    override val persistenceId: String = name
    override val viewId: String = name + "-view"

    override def autoUpdateInterval: FiniteDuration = 50.millis
    override def autoUpdateReplayMax: Long = 2

    def receive = {
      case payload ⇒
        probe ! s"replicated-${payload}-${lastSequenceNr}"
    }
  }

  private class BecomingPersistentView(name: String, probe: ActorRef) extends PersistentView {
    override def persistenceId = name
    override def viewId = name + "-view"

    def receive = Actor.emptyBehavior

    context.become {
      case payload ⇒ probe ! s"replicated-${payload}-${lastSequenceNr}"
    }
  }

  private class StashingPersistentView(name: String, probe: ActorRef) extends PersistentView {
    override def persistenceId = name
    override def viewId = name + "-view"

    def receive = {
      case "other" ⇒ stash()
      case "unstash" ⇒
        unstashAll()
        context.become {
          case msg ⇒ probe ! s"$msg-${lastSequenceNr}"
        }
      case msg ⇒ stash()
    }
  }

  private class PersistentOrNotTestPersistentView(name: String, probe: ActorRef) extends PersistentView {
    override val persistenceId: String = name
    override val viewId: String = name + "-view"

    def receive = {
      case payload if isPersistent ⇒ probe ! s"replicated-${payload}-${lastSequenceNr}"
      case payload                 ⇒ probe ! s"normal-${payload}-${lastSequenceNr}"
    }
  }

  private class SnapshottingPersistentView(name: String, probe: ActorRef) extends PersistentView {
    override val persistenceId: String = name
    override val viewId: String = s"${name}-replicator"

    override def autoUpdateInterval: FiniteDuration = 100.microseconds.dilated(context.system)

    var last: String = _

    def receive = {
      case "get" ⇒
        probe ! last
      case "snap" ⇒
        saveSnapshot(last)
      case "restart" ⇒
        throw new TestException("restart requested")
      case SaveSnapshotSuccess(_) ⇒
        probe ! "snapped"
      case SnapshotOffer(metadata, snapshot: String) ⇒
        last = snapshot
        probe ! last
      case payload ⇒
        last = s"replicated-${payload}-${lastSequenceNr}"
        probe ! last
    }
  }
}

abstract class PersistentViewSpec(config: Config) extends PersistenceSpec(config) with ImplicitSender {
  import akka.persistence.PersistentViewSpec._

  var persistentActor: ActorRef = _
  var view: ActorRef = _

  var persistentActorProbe: TestProbe = _
  var viewProbe: TestProbe = _

  override protected def beforeEach(): Unit = {
    super.beforeEach()

    persistentActorProbe = TestProbe()
    viewProbe = TestProbe()

    persistentActor = system.actorOf(Props(classOf[TestPersistentActor], name, persistentActorProbe.ref))
    persistentActor ! "a"
    persistentActor ! "b"

    persistentActorProbe.expectMsg("a-1")
    persistentActorProbe.expectMsg("b-2")
  }

  override protected def afterEach(): Unit = {
    system.stop(persistentActor)
    system.stop(view)
    super.afterEach()
  }

  def subscribeToReplay(probe: TestProbe): Unit =
    system.eventStream.subscribe(probe.ref, classOf[ReplayMessages])

  "A persistent view" must {
    "receive past updates from a persistent actor" in {
      view = system.actorOf(Props(classOf[TestPersistentView], name, viewProbe.ref))
      viewProbe.expectMsg("replicated-a-1")
      viewProbe.expectMsg("replicated-b-2")
    }
    "receive live updates from a persistent actor" in {
      view = system.actorOf(Props(classOf[TestPersistentView], name, viewProbe.ref))
      viewProbe.expectMsg("replicated-a-1")
      viewProbe.expectMsg("replicated-b-2")
      persistentActor ! "c"
      viewProbe.expectMsg("replicated-c-3")
    }
    "run updates at specified interval" in {
      view = system.actorOf(Props(classOf[TestPersistentView], name, viewProbe.ref, 2.seconds))
      // initial update is done on start
      viewProbe.expectMsg("replicated-a-1")
      viewProbe.expectMsg("replicated-b-2")
      // live updates takes 5 seconds to replicate
      persistentActor ! "c"
      viewProbe.expectNoMsg(1.second)
      viewProbe.expectMsg("replicated-c-3")
    }
    "run updates on user request" in {
      view = system.actorOf(Props(classOf[TestPersistentView], name, viewProbe.ref, 5.seconds))
      viewProbe.expectMsg("replicated-a-1")
      viewProbe.expectMsg("replicated-b-2")
      persistentActor ! "c"
      persistentActorProbe.expectMsg("c-3")
      view ! Update(await = false)
      viewProbe.expectMsg("replicated-c-3")
    }
    "run updates on user request and await update" in {
      view = system.actorOf(Props(classOf[TestPersistentView], name, viewProbe.ref, 5.seconds))
      viewProbe.expectMsg("replicated-a-1")
      viewProbe.expectMsg("replicated-b-2")
      persistentActor ! "c"
      persistentActorProbe.expectMsg("c-3")
      view ! Update(await = true)
      view ! "get"
      viewProbe.expectMsg("replicated-c-3")
    }
    "run updates again on failure outside an update cycle" in {
      view = system.actorOf(Props(classOf[TestPersistentView], name, viewProbe.ref, 5.seconds))
      viewProbe.expectMsg("replicated-a-1")
      viewProbe.expectMsg("replicated-b-2")
      view ! "boom"
      viewProbe.expectMsg("replicated-a-1")
      viewProbe.expectMsg("replicated-b-2")
    }
    "run updates again on failure during an update cycle" in {
      persistentActor ! "c"
      persistentActorProbe.expectMsg("c-3")
      view = system.actorOf(Props(classOf[TestPersistentView], name, viewProbe.ref, 5.seconds, Some("b")))
      viewProbe.expectMsg("replicated-a-1")
      viewProbe.expectMsg("replicated-a-1")
      viewProbe.expectMsg("replicated-b-2")
      viewProbe.expectMsg("replicated-c-3")
    }
    "run size-limited updates on user request" in {
      persistentActor ! "c"
      persistentActor ! "d"
      persistentActor ! "e"
      persistentActor ! "f"

      persistentActorProbe.expectMsg("c-3")
      persistentActorProbe.expectMsg("d-4")
      persistentActorProbe.expectMsg("e-5")
      persistentActorProbe.expectMsg("f-6")

      view = system.actorOf(Props(classOf[PassiveTestPersistentView], name, viewProbe.ref, None))

      view ! Update(await = true, replayMax = 2)
      view ! "get"
      viewProbe.expectMsg("replicated-b-2")

      view ! Update(await = true, replayMax = 1)
      view ! "get"
      viewProbe.expectMsg("replicated-c-3")

      view ! Update(await = true, replayMax = 4)
      view ! "get"
      viewProbe.expectMsg("replicated-f-6")
    }
    "run size-limited updates automatically" in {
      val replayProbe = TestProbe()

      persistentActor ! "c"
      persistentActor ! "d"

      persistentActorProbe.expectMsg("c-3")
      persistentActorProbe.expectMsg("d-4")

      subscribeToReplay(replayProbe)

      view = system.actorOf(Props(classOf[ActiveTestPersistentView], name, viewProbe.ref))

      viewProbe.expectMsg("replicated-a-1")
      viewProbe.expectMsg("replicated-b-2")
      viewProbe.expectMsg("replicated-c-3")
      viewProbe.expectMsg("replicated-d-4")

      replayProbe.expectMsgPF() { case ReplayMessages(1L, _, 2L, _, _) ⇒ }
      replayProbe.expectMsgPF() { case ReplayMessages(3L, _, 2L, _, _) ⇒ }
      replayProbe.expectMsgPF() { case ReplayMessages(5L, _, 2L, _, _) ⇒ }
    }
    "support context.become" in {
      view = system.actorOf(Props(classOf[BecomingPersistentView], name, viewProbe.ref))
      viewProbe.expectMsg("replicated-a-1")
      viewProbe.expectMsg("replicated-b-2")
    }
    "check if an incoming message is persistent" in {
      persistentActor ! "c"

      persistentActorProbe.expectMsg("c-3")

      view = system.actorOf(Props(classOf[PersistentOrNotTestPersistentView], name, viewProbe.ref))

      view ! "d"
      view ! "e"

      viewProbe.expectMsg("replicated-a-1")
      viewProbe.expectMsg("replicated-b-2")
      viewProbe.expectMsg("replicated-c-3")
      viewProbe.expectMsg("normal-d-3")
      viewProbe.expectMsg("normal-e-3")

      persistentActor ! "f"
      viewProbe.expectMsg("replicated-f-4")
    }
    "take snapshots" in {
      view = system.actorOf(Props(classOf[SnapshottingPersistentView], name, viewProbe.ref))
      viewProbe.expectMsg("replicated-a-1")
      viewProbe.expectMsg("replicated-b-2")
      view ! "snap"
      viewProbe.expectMsg("snapped")
      view ! "restart"
      persistentActor ! "c"
      viewProbe.expectMsg("replicated-b-2")
      viewProbe.expectMsg("replicated-c-3")
    }
    "support stash" in {
      view = system.actorOf(Props(classOf[StashingPersistentView], name, viewProbe.ref))
      view ! "other"
      view ! "unstash"
      viewProbe.expectMsg("a-2") // note that the lastSequenceNumber is 2, since we have replayed b-2
      viewProbe.expectMsg("b-2")
      viewProbe.expectMsg("other-2")
    }
  }
}

class LeveldbPersistentViewSpec extends PersistentViewSpec(PersistenceSpec.config("leveldb", "LeveldbPersistentViewSpec"))
class InmemPersistentViewSpec extends PersistentViewSpec(PersistenceSpec.config("inmem", "InmemPersistentViewSpec"))

