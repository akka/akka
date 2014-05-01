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

class PersistentPublisherSpec extends AkkaSpec(PersistenceSpec.config("leveldb", "ViewProducerSpec", serialization = "off")) with PersistenceSpec {
  import PersistentPublisherSpec._

  val numMessages = 10

  val publisherSettings = PersistentPublisherSettings(idle = Some(100.millis))
  val materializer = FlowMaterializer(MaterializerSettings())

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

  "A view producer" must {
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

  "A view producer" can {
    "have several consumers" in {
      val streamProbe1 = TestProbe()
      val streamProbe2 = TestProbe()

      val producer = PersistentFlow.fromProcessor(processorId(1), publisherSettings).toProducer(materializer)

      Flow(producer).foreach {
        case Persistent(payload, sequenceNr) ⇒ streamProbe1.ref ! s"${payload}-${sequenceNr}"
      }.consume(materializer)

      // let consumer consume all existing messages
      1 to numMessages foreach { i ⇒
        streamProbe1.expectMsg(s"a-${i}")
      }

      // subscribe another consumer
      Flow(producer).foreach {
        case Persistent(payload, sequenceNr) ⇒ streamProbe2.ref ! s"${payload}-${sequenceNr}"
      }.consume(materializer)

      // produce new messages and let both consumers handle them
      1 to 2 foreach { i ⇒
        processor1 ! Persistent("a")
        streamProbe1.expectMsg(s"a-${numMessages + i}")
        streamProbe2.expectMsg(s"a-${numMessages + i}")
      }
    }
  }

  "A consumer" can {
    "consume from several view producers" in {
      val streamProbe1 = TestProbe()
      val streamProbe2 = TestProbe()

      val fromSequenceNr1 = 7L
      val fromSequenceNr2 = 3L

      val producer1 = PersistentFlow.fromProcessor(processorId(1), publisherSettings.copy(fromSequenceNr = fromSequenceNr1)).toProducer(materializer)
      val producer2 = PersistentFlow.fromProcessor(processorId(2), publisherSettings.copy(fromSequenceNr = fromSequenceNr2)).toProducer(materializer)

      Flow(producer1).merge(producer2).foreach {
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
