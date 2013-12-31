package akka.persistence

import scala.concurrent.duration._

import com.typesafe.config.Config

import akka.actor._
import akka.persistence.JournalProtocol.Confirm
import akka.testkit._

object ReplicatorSpec {
  class TestProcessor(name: String, probe: ActorRef) extends NamedProcessor(name) {
    def receive = {
      case Persistent(payload, sequenceNr) ⇒
        probe ! s"${payload}-${sequenceNr}"
    }
  }

  class TestReplicator(name: String, probe: ActorRef, interval: FiniteDuration, var failAt: Option[String]) extends Replicator {
    def this(name: String, probe: ActorRef, interval: FiniteDuration) =
      this(name, probe, interval, None)

    def this(name: String, probe: ActorRef) =
      this(name, probe, 100.milliseconds)

    override def replicationInterval: FiniteDuration = interval
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

  class TestDestination(probe: ActorRef) extends Actor {
    def receive = {
      case cp @ ConfirmablePersistent(payload, sequenceNr, _) ⇒
        cp.confirm()
        probe ! s"${payload}-${sequenceNr}"
    }
  }

  class EmittingReplicator(name: String, destination: ActorRef) extends Replicator {
    override def replicationInterval: FiniteDuration = 100.milliseconds
    override val processorId: String = name

    val channel = context.actorOf(Channel.props(s"${name}-channel"))

    def receive = {
      case "restart" ⇒
        throw new TestException("restart requested")
      case Persistent(payload, sequenceNr) ⇒
        channel ! Deliver(Persistent(s"emitted-${payload}"), destination)
    }
  }

  class SnapshottingReplicator(name: String, probe: ActorRef) extends Replicator {
    override def replicationInterval: FiniteDuration = 100.microseconds
    override val processorId: String = name
    override val replicatorId: String = s"${name}-replicator"

    var last: String = _

    def receive = {
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

abstract class ReplicatorSpec(config: Config) extends AkkaSpec(config) with PersistenceSpec with ImplicitSender {
  import ReplicatorSpec._

  var processor: ActorRef = _
  var replicator: ActorRef = _

  var processorProbe: TestProbe = _
  var replicatorProbe: TestProbe = _

  override protected def beforeEach(): Unit = {
    super.beforeEach()

    processorProbe = TestProbe()
    replicatorProbe = TestProbe()

    processor = system.actorOf(Props(classOf[TestProcessor], name, processorProbe.ref))
    processor ! Persistent("a")
    processor ! Persistent("b")

    processorProbe.expectMsg("a-1")
    processorProbe.expectMsg("b-2")
  }

  override protected def afterEach(): Unit = {
    system.stop(processor)
    system.stop(replicator)
    super.afterEach()
  }

  def subscribeToConfirmation(probe: TestProbe): Unit =
    system.eventStream.subscribe(probe.ref, classOf[Confirm])

  def awaitConfirmation(probe: TestProbe): Unit =
    probe.expectMsgType[Confirm]

  "A replicator" must {
    "replicate messages from a processor" in {
      replicator = system.actorOf(Props(classOf[TestReplicator], name, replicatorProbe.ref))
      replicatorProbe.expectMsg("replicated-a-1")
      replicatorProbe.expectMsg("replicated-b-2")
    }
    "receive live updates from a processor" in {
      replicator = system.actorOf(Props(classOf[TestReplicator], name, replicatorProbe.ref))
      replicatorProbe.expectMsg("replicated-a-1")
      replicatorProbe.expectMsg("replicated-b-2")
      processor ! Persistent("c")
      replicatorProbe.expectMsg("replicated-c-3")
    }
    "replicate at specified interval" in {
      replicator = system.actorOf(Props(classOf[TestReplicator], name, replicatorProbe.ref, 2.seconds))
      // initial replication is done on start
      replicatorProbe.expectMsg("replicated-a-1")
      replicatorProbe.expectMsg("replicated-b-2")
      // live updates takes 5 seconds to replicate
      processor ! Persistent("c")
      replicatorProbe.expectNoMsg(1.second)
      replicatorProbe.expectMsg("replicated-c-3")
    }
    "replicate on user request" in {
      replicator = system.actorOf(Props(classOf[TestReplicator], name, replicatorProbe.ref, 5.seconds))
      replicatorProbe.expectMsg("replicated-a-1")
      replicatorProbe.expectMsg("replicated-b-2")
      processor ! Persistent("c")
      processorProbe.expectMsg("c-3")
      replicator ! Replicate(awaitReplication = false)
      replicatorProbe.expectMsg("replicated-c-3")
    }
    "replicate on user request and await replication" in {
      replicator = system.actorOf(Props(classOf[TestReplicator], name, replicatorProbe.ref, 5.seconds))
      replicatorProbe.expectMsg("replicated-a-1")
      replicatorProbe.expectMsg("replicated-b-2")
      processor ! Persistent("c")
      processorProbe.expectMsg("c-3")
      replicator ! Replicate(awaitReplication = true)
      replicator ! "get"
      replicatorProbe.expectMsg("replicated-c-3")
    }
    "replicate again on failure outside a replication cycle" in {
      replicator = system.actorOf(Props(classOf[TestReplicator], name, replicatorProbe.ref, 5.seconds))
      replicatorProbe.expectMsg("replicated-a-1")
      replicatorProbe.expectMsg("replicated-b-2")
      replicator ! "boom"
      replicatorProbe.expectMsg("replicated-a-1")
      replicatorProbe.expectMsg("replicated-b-2")
    }
    "replicate again on failure during a replication cycle" in {
      processor ! Persistent("c")
      processorProbe.expectMsg("c-3")
      replicator = system.actorOf(Props(classOf[TestReplicator], name, replicatorProbe.ref, 5.seconds, Some("b")))
      replicatorProbe.expectMsg("replicated-a-1")
      replicatorProbe.expectMsg("replicated-a-1")
      replicatorProbe.expectMsg("replicated-b-2")
      replicatorProbe.expectMsg("replicated-c-3")
    }
  }

  "A replicator" can {
    "use channels" in {
      val confirmProbe = TestProbe()
      val destinationProbe = TestProbe()
      val destination = system.actorOf(Props(classOf[TestDestination], destinationProbe.ref))

      subscribeToConfirmation(confirmProbe)

      replicator = system.actorOf(Props(classOf[EmittingReplicator], name, destination))
      destinationProbe.expectMsg("emitted-a-1")
      destinationProbe.expectMsg("emitted-b-2")
      awaitConfirmation(confirmProbe)
      awaitConfirmation(confirmProbe)

      replicator ! "restart"
      processor ! Persistent("c")

      destinationProbe.expectMsg("emitted-c-3")
      awaitConfirmation(confirmProbe)
    }
    "take snapshots" in {
      replicator = system.actorOf(Props(classOf[SnapshottingReplicator], name, replicatorProbe.ref))
      replicatorProbe.expectMsg("replicated-a-1")
      replicatorProbe.expectMsg("replicated-b-2")
      replicator ! "snap"
      replicatorProbe.expectMsg("snapped")
      replicator ! "restart"
      processor ! Persistent("c")
      replicatorProbe.expectMsg("replicated-b-2")
      replicatorProbe.expectMsg("replicated-c-3")
    }
  }
}

class LeveldbReplicatorSpec extends ReplicatorSpec(PersistenceSpec.config("leveldb", "LeveldbReplicatorSpec"))
class InmemReplicatorSpec extends ReplicatorSpec(PersistenceSpec.config("inmem", "InmemReplicatorSpec"))

