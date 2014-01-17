/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.persistence

import scala.concurrent.duration._

import com.typesafe.config.Config

import akka.actor._
import akka.testkit._

object ViewSpec {
  class TestProcessor(name: String, probe: ActorRef) extends NamedProcessor(name) {
    def receive = {
      case Persistent(payload, sequenceNr) ⇒
        probe ! s"${payload}-${sequenceNr}"
    }
  }

  class TestView(name: String, probe: ActorRef, interval: FiniteDuration, var failAt: Option[String]) extends View {
    def this(name: String, probe: ActorRef, interval: FiniteDuration) =
      this(name, probe, interval, None)

    def this(name: String, probe: ActorRef) =
      this(name, probe, 100.milliseconds)

    override def autoUpdateInterval: FiniteDuration = interval.dilated(context.system)
    override val processorId: String = name

    var last: String = _

    def receive = {
      case "get" ⇒
        probe ! last
      case "boom" ⇒
        throw new TestException("boom")
      case Persistent(payload, _) if Some(payload) == failAt ⇒
        throw new TestException("boom")
      case Persistent(payload, sequenceNr) ⇒
        last = s"replicated-${payload}-${sequenceNr}"
        probe ! last
    }

    override def postRestart(reason: Throwable): Unit = {
      super.postRestart(reason)
      failAt = None
    }
  }

  class PassiveTestView(name: String, probe: ActorRef, var failAt: Option[String]) extends View {
    override val processorId: String = name

    override def autoUpdate: Boolean = false
    override def autoUpdateReplayMax: Long = 0L // no message replay during initial recovery

    var last: String = _

    def receive = {
      case "get" ⇒
        probe ! last
      case Persistent(payload, _) if Some(payload) == failAt ⇒
        throw new TestException("boom")
      case Persistent(payload, sequenceNr) ⇒
        last = s"replicated-${payload}-${sequenceNr}"
    }

    override def postRestart(reason: Throwable): Unit = {
      super.postRestart(reason)
      failAt = None
    }
  }

  class TestDestination(probe: ActorRef) extends Actor {
    def receive = {
      case cp @ ConfirmablePersistent(payload, sequenceNr, _) ⇒
        cp.confirm()
        probe ! s"${payload}-${sequenceNr}"
    }
  }

  class EmittingView(name: String, destination: ActorRef) extends View {
    override def autoUpdateInterval: FiniteDuration = 100.milliseconds.dilated(context.system)
    override val processorId: String = name

    val channel = context.actorOf(Channel.props(s"${name}-channel"))

    def receive = {
      case "restart" ⇒
        throw new TestException("restart requested")
      case Persistent(payload, sequenceNr) ⇒
        channel ! Deliver(Persistent(s"emitted-${payload}"), destination.path)
    }
  }

  class SnapshottingView(name: String, probe: ActorRef) extends View {
    override def autoUpdateInterval: FiniteDuration = 100.microseconds.dilated(context.system)
    override val processorId: String = name
    override val viewId: String = s"${name}-replicator"

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
      case Persistent(payload, sequenceNr) ⇒
        last = s"replicated-${payload}-${sequenceNr}"
        probe ! last
    }
  }
}

abstract class ViewSpec(config: Config) extends AkkaSpec(config) with PersistenceSpec with ImplicitSender {
  import ViewSpec._

  var processor: ActorRef = _
  var view: ActorRef = _

  var processorProbe: TestProbe = _
  var viewProbe: TestProbe = _

  override protected def beforeEach(): Unit = {
    super.beforeEach()

    processorProbe = TestProbe()
    viewProbe = TestProbe()

    processor = system.actorOf(Props(classOf[TestProcessor], name, processorProbe.ref))
    processor ! Persistent("a")
    processor ! Persistent("b")

    processorProbe.expectMsg("a-1")
    processorProbe.expectMsg("b-2")
  }

  override protected def afterEach(): Unit = {
    system.stop(processor)
    system.stop(view)
    super.afterEach()
  }

  def subscribeToConfirmation(probe: TestProbe): Unit =
    system.eventStream.subscribe(probe.ref, classOf[Delivered])

  def awaitConfirmation(probe: TestProbe): Unit =
    probe.expectMsgType[Delivered]

  "A view" must {
    "receive past updates from a processor" in {
      view = system.actorOf(Props(classOf[TestView], name, viewProbe.ref))
      viewProbe.expectMsg("replicated-a-1")
      viewProbe.expectMsg("replicated-b-2")
    }
    "receive live updates from a processor" in {
      view = system.actorOf(Props(classOf[TestView], name, viewProbe.ref))
      viewProbe.expectMsg("replicated-a-1")
      viewProbe.expectMsg("replicated-b-2")
      processor ! Persistent("c")
      viewProbe.expectMsg("replicated-c-3")
    }
    "run updates at specified interval" in {
      view = system.actorOf(Props(classOf[TestView], name, viewProbe.ref, 2.seconds))
      // initial update is done on start
      viewProbe.expectMsg("replicated-a-1")
      viewProbe.expectMsg("replicated-b-2")
      // live updates takes 5 seconds to replicate
      processor ! Persistent("c")
      viewProbe.expectNoMsg(1.second)
      viewProbe.expectMsg("replicated-c-3")
    }
    "run updates on user request" in {
      view = system.actorOf(Props(classOf[TestView], name, viewProbe.ref, 5.seconds))
      viewProbe.expectMsg("replicated-a-1")
      viewProbe.expectMsg("replicated-b-2")
      processor ! Persistent("c")
      processorProbe.expectMsg("c-3")
      view ! Update(await = false)
      viewProbe.expectMsg("replicated-c-3")
    }
    "run updates on user request and await update" in {
      view = system.actorOf(Props(classOf[TestView], name, viewProbe.ref, 5.seconds))
      viewProbe.expectMsg("replicated-a-1")
      viewProbe.expectMsg("replicated-b-2")
      processor ! Persistent("c")
      processorProbe.expectMsg("c-3")
      view ! Update(await = true)
      view ! "get"
      viewProbe.expectMsg("replicated-c-3")
    }
    "run updates again on failure outside an update cycle" in {
      view = system.actorOf(Props(classOf[TestView], name, viewProbe.ref, 5.seconds))
      viewProbe.expectMsg("replicated-a-1")
      viewProbe.expectMsg("replicated-b-2")
      view ! "boom"
      viewProbe.expectMsg("replicated-a-1")
      viewProbe.expectMsg("replicated-b-2")
    }
    "run updates again on failure during an update cycle" in {
      processor ! Persistent("c")
      processorProbe.expectMsg("c-3")
      view = system.actorOf(Props(classOf[TestView], name, viewProbe.ref, 5.seconds, Some("b")))
      viewProbe.expectMsg("replicated-a-1")
      viewProbe.expectMsg("replicated-a-1")
      viewProbe.expectMsg("replicated-b-2")
      viewProbe.expectMsg("replicated-c-3")
    }
    "run size-limited updates on user request" in {
      processor ! Persistent("c")
      processor ! Persistent("d")
      processor ! Persistent("e")
      processor ! Persistent("f")

      processorProbe.expectMsg("c-3")
      processorProbe.expectMsg("d-4")
      processorProbe.expectMsg("e-5")
      processorProbe.expectMsg("f-6")

      view = system.actorOf(Props(classOf[PassiveTestView], name, viewProbe.ref, None))

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
  }

  "A view" can {
    "use channels" in {
      val confirmProbe = TestProbe()
      val destinationProbe = TestProbe()
      val destination = system.actorOf(Props(classOf[TestDestination], destinationProbe.ref))

      subscribeToConfirmation(confirmProbe)

      view = system.actorOf(Props(classOf[EmittingView], name, destination))
      destinationProbe.expectMsg("emitted-a-1")
      destinationProbe.expectMsg("emitted-b-2")
      awaitConfirmation(confirmProbe)
      awaitConfirmation(confirmProbe)

      view ! "restart"
      processor ! Persistent("c")

      destinationProbe.expectMsg("emitted-c-3")
      awaitConfirmation(confirmProbe)
    }
    "take snapshots" in {
      view = system.actorOf(Props(classOf[SnapshottingView], name, viewProbe.ref))
      viewProbe.expectMsg("replicated-a-1")
      viewProbe.expectMsg("replicated-b-2")
      view ! "snap"
      viewProbe.expectMsg("snapped")
      view ! "restart"
      processor ! Persistent("c")
      viewProbe.expectMsg("replicated-b-2")
      viewProbe.expectMsg("replicated-c-3")
    }
  }
}

class LeveldbViewSpec extends ViewSpec(PersistenceSpec.config("leveldb", "LeveldbViewSpec"))
class InmemViewSpec extends ViewSpec(PersistenceSpec.config("inmem", "InmemViewSpec"))

