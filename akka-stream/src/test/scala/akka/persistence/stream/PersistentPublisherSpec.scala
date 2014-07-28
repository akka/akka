/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.persistence.stream

import scala.concurrent.duration._

import akka.actor._
import akka.persistence._
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.testkit._
import akka.testkit.TestProbe

// ------------------------------------------------------------------------------------------------
// FIXME: move this file to akka-persistence-experimental once going back to project dependencies
// ------------------------------------------------------------------------------------------------

object PersistentPublisherSpec {
  class TestProcessor(name: String, probe: ActorRef) extends NamedProcessor(name) {
    def receive = {
      case Persistent(payload, sequenceNr) ⇒ probe ! s"${payload}-${sequenceNr}"
    }
  }
}

class PersistentPublisherSpec extends AkkaSpec(PersistenceSpec.config("leveldb", "ViewPublisherSpec", serialization = "off")) with PersistenceSpec {
  import PersistentPublisherSpec._

  val numMessages = 10

  val publisherSettings = PersistentPublisherSettings(idle = Some(100.millis))
  val materializer = FlowMaterializer(MaterializerSettings(dispatcher = "akka.test.stream-dispatcher"))

  var processor1: ActorRef = _
  var processor2: ActorRef = _

  var processor1Probe: TestProbe = _
  var processor2Probe: TestProbe = _

  def processorId(num: Int): String =
    name + num

  override protected def beforeEach(): Unit = {
    super.beforeEach()

    processor1Probe = TestProbe()
    processor2Probe = TestProbe()

    processor1 = system.actorOf(Props(classOf[TestProcessor], processorId(1), processor1Probe.ref))
    processor2 = system.actorOf(Props(classOf[TestProcessor], processorId(2), processor2Probe.ref))

    1 to numMessages foreach { i ⇒
      processor1 ! Persistent("a")
      processor2 ! Persistent("b")

      processor1Probe.expectMsg(s"a-${i}")
      processor2Probe.expectMsg(s"b-${i}")
    }
  }

  override protected def afterEach(): Unit = {
    system.stop(processor1)
    system.stop(processor1)
    super.afterEach()
  }

  "A view publisher" must {
    "pull existing messages from a processor's journal" in {
      val streamProbe = TestProbe()

      PersistentFlow.fromProcessor(processorId(1), publisherSettings).foreach {
        case Persistent(payload, sequenceNr) ⇒ streamProbe.ref ! s"${payload}-${sequenceNr}"
      }.consume(materializer)

      1 to numMessages foreach { i ⇒
        streamProbe.expectMsg(s"a-${i}")
      }
    }
    "pull existing messages and new from a processor's journal" in {
      val streamProbe = TestProbe()

      PersistentFlow.fromProcessor(processorId(1), publisherSettings).foreach {
        case Persistent(payload, sequenceNr) ⇒ streamProbe.ref ! s"${payload}-${sequenceNr}"
      }.consume(materializer)

      1 to numMessages foreach { i ⇒
        streamProbe.expectMsg(s"a-${i}")
      }

      processor1 ! Persistent("a")
      processor1 ! Persistent("a")

      streamProbe.expectMsg(s"a-${numMessages + 1}")
      streamProbe.expectMsg(s"a-${numMessages + 2}")
    }
    "pull existing messages from a processor's journal starting form a specified sequence number" in {
      val streamProbe = TestProbe()
      val fromSequenceNr = 5L

      PersistentFlow.fromProcessor(processorId(1), publisherSettings.copy(fromSequenceNr = fromSequenceNr)).foreach {
        case Persistent(payload, sequenceNr) ⇒ streamProbe.ref ! s"${payload}-${sequenceNr}"
      }.consume(materializer)

      fromSequenceNr to numMessages foreach { i ⇒
        streamProbe.expectMsg(s"a-${i}")
      }
    }
  }

  "A view publisher" can {
    "have several subscribers" in {
      val streamProbe1 = TestProbe()
      val streamProbe2 = TestProbe()

      val publisher = PersistentFlow.fromProcessor(processorId(1), publisherSettings).toPublisher(materializer)

      Flow(publisher).foreach {
        case Persistent(payload, sequenceNr) ⇒ streamProbe1.ref ! s"${payload}-${sequenceNr}"
      }.consume(materializer)

      // let subscriber consume all existing messages
      1 to numMessages foreach { i ⇒
        streamProbe1.expectMsg(s"a-${i}")
      }

      // subscribe another subscriber
      Flow(publisher).foreach {
        case Persistent(payload, sequenceNr) ⇒ streamProbe2.ref ! s"${payload}-${sequenceNr}"
      }.consume(materializer)

      // produce new messages and let both subscribers handle them
      1 to 2 foreach { i ⇒
        processor1 ! Persistent("a")
        streamProbe1.expectMsg(s"a-${numMessages + i}")
        streamProbe2.expectMsg(s"a-${numMessages + i}")
      }
    }
  }

  "A subscriber" can {
    "consume from several view publishers" in {
      val streamProbe1 = TestProbe()
      val streamProbe2 = TestProbe()

      val fromSequenceNr1 = 7L
      val fromSequenceNr2 = 3L

      val publisher1 = PersistentFlow.fromProcessor(processorId(1), publisherSettings.copy(fromSequenceNr = fromSequenceNr1)).toPublisher(materializer)
      val publisher2 = PersistentFlow.fromProcessor(processorId(2), publisherSettings.copy(fromSequenceNr = fromSequenceNr2)).toPublisher(materializer)

      Flow(publisher1).merge(publisher2).foreach {
        case Persistent(payload: String, sequenceNr) if (payload.startsWith("a")) ⇒ streamProbe1.ref ! s"${payload}-${sequenceNr}"
        case Persistent(payload: String, sequenceNr) if (payload.startsWith("b")) ⇒ streamProbe2.ref ! s"${payload}-${sequenceNr}"
      }.consume(materializer)

      1 to numMessages foreach { i ⇒
        if (i >= fromSequenceNr1) streamProbe1.expectMsg(s"a-${i}")
        if (i >= fromSequenceNr2) streamProbe2.expectMsg(s"b-${i}")
      }
    }
  }
}
