/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.persistence.stream

import akka.actor.ActorRef
import akka.actor.Props
import akka.persistence.RecoveryCompleted
import akka.stream.scaladsl.FlowGraph
import akka.stream.scaladsl.FlowGraphImplicits
import akka.stream.FlowMaterializer
import akka.stream.scaladsl.Merge
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.testkit.AkkaSpec
import akka.testkit.TestProbe

import scala.concurrent.duration._

// ------------------------------------------------------------------------------------------------
// FIXME: #15964 move this file to akka-persistence-experimental once going back to project dependencies
// ------------------------------------------------------------------------------------------------

object PersistentSourceSpec {
  class TestPersistentActor(name: String, probe: ActorRef) extends NamedPersistentActor(name) {
    override def receiveCommand = {
      case cmd ⇒ persist(cmd) { event ⇒ probe ! s"${event}-${lastSequenceNr}" }
    }
    override def receiveRecover = {
      case RecoveryCompleted ⇒ // ignore
      case event             ⇒ probe ! s"${event}-${lastSequenceNr}"
    }
  }
}

class PersistentSourceSpec extends AkkaSpec(PersistenceSpec.config("leveldb", "PersistentSourceSpec", serialization = "off")) with PersistenceSpec {
  import PersistentSourceSpec._

  val numMessages = 10

  val sourceSettings = PersistentSourceSettings(idle = Some(100.millis))
  implicit val materializer = FlowMaterializer()

  var persistentActor1: ActorRef = _
  var persistentActor2: ActorRef = _

  var persistentActor1Probe: TestProbe = _
  var persistentActor2Probe: TestProbe = _

  def persistenceId(num: Int): String =
    name + num

  override protected def beforeEach(): Unit = {
    super.beforeEach()

    persistentActor1Probe = TestProbe()
    persistentActor2Probe = TestProbe()

    persistentActor1 = system.actorOf(Props(classOf[TestPersistentActor], persistenceId(1), persistentActor1Probe.ref))
    persistentActor2 = system.actorOf(Props(classOf[TestPersistentActor], persistenceId(2), persistentActor2Probe.ref))

    1 to numMessages foreach { i ⇒
      persistentActor1 ! ("a" + i)
      persistentActor2 ! ("b" + i)

      persistentActor1Probe.expectMsg(s"a$i-$i")
      persistentActor2Probe.expectMsg(s"b$i-$i")
    }
  }

  override protected def afterEach(): Unit = {
    system.stop(persistentActor1)
    system.stop(persistentActor1)
    super.afterEach()
  }

  "A PersistentSource" must {

    "pull existing events from a persistent actor's journal" in {
      val streamProbe = TestProbe()

      PersistentSource[String](persistenceId(1), sourceSettings).foreach {
        case event ⇒ streamProbe.ref ! event
      }

      1 to numMessages foreach { i ⇒
        streamProbe.expectMsg(s"a$i")
      }
    }

    "pull existing events and new from a persistent actor's journal" in {
      val streamProbe = TestProbe()

      PersistentSource[String](persistenceId(1), sourceSettings).foreach {
        case event ⇒ streamProbe.ref ! event
      }

      1 to numMessages foreach { i ⇒
        streamProbe.expectMsg(s"a$i")
      }

      persistentActor1 ! s"a${numMessages + 1}"
      persistentActor1 ! s"a${numMessages + 2}"

      streamProbe.expectMsg(s"a${numMessages + 1}")
      streamProbe.expectMsg(s"a${numMessages + 2}")
    }

    "pull existing events from a persistent actor's journal starting form a specified sequence number" in {
      val streamProbe = TestProbe()
      val fromSequenceNr = 5L

      PersistentSource[String](persistenceId(1), sourceSettings.copy(fromSequenceNr = fromSequenceNr)).foreach {
        case event ⇒ streamProbe.ref ! event
      }

      fromSequenceNr to numMessages foreach { i ⇒
        streamProbe.expectMsg(s"a$i")
      }
    }

    "work with FanoutPublisher" in {
      val streamProbe1 = TestProbe()
      val streamProbe2 = TestProbe()

      val publisher = PersistentSource[String](persistenceId(1), sourceSettings).runWith(Sink.fanoutPublisher(4, 16))

      Source[String](publisher).foreach {
        case event ⇒ streamProbe1.ref ! event
      }

      // let subscriber consume all existing events
      1 to numMessages foreach { i ⇒
        streamProbe1.expectMsg(s"a$i")
      }

      // subscribe another subscriber
      Source[String](publisher).foreach {
        case event ⇒ streamProbe2.ref ! event
      }

      // produce new events and let both subscribers handle them
      1 to 2 foreach { i ⇒
        persistentActor1 ! s"a${numMessages + i}"
        streamProbe1.expectMsg(s"a${numMessages + i}")
        streamProbe2.expectMsg(s"a${numMessages + i}")
      }
    }

    "work in FlowGraph" in {
      val streamProbe1 = TestProbe()
      val streamProbe2 = TestProbe()

      val fromSequenceNr1 = 7L
      val fromSequenceNr2 = 3L

      val source1 = PersistentSource[String](persistenceId(1), sourceSettings.copy(fromSequenceNr = fromSequenceNr1))
      val source2 = PersistentSource[String](persistenceId(2), sourceSettings.copy(fromSequenceNr = fromSequenceNr2))

      val sink = Sink.foreach[String] {
        case event: String if event.startsWith("a") ⇒ streamProbe1.ref ! event
        case event: String if event.startsWith("b") ⇒ streamProbe2.ref ! event
      }

      FlowGraph { implicit b ⇒
        import FlowGraphImplicits._
        val merge = Merge[String]
        source1 ~> merge ~> sink
        source2 ~> merge
      }.run()

      1 to numMessages foreach { i ⇒
        if (i >= fromSequenceNr1) streamProbe1.expectMsg(s"a$i")
        if (i >= fromSequenceNr2) streamProbe2.expectMsg(s"b$i")
      }
    }
  }

}
