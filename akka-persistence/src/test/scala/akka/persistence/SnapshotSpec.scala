/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence

import akka.actor._
import akka.testkit._

object SnapshotSpec {
  val config =
    """
      |serialize-creators = on
      |serialize-messages = on
      |akka.persistence.journal.leveldb.dir = "target/journal-snapshot-spec"
      |akka.persistence.snapshot-store.local.dir = ${akka.persistence.journal.leveldb.dir}/snapshots
    """.stripMargin

  case object TakeSnapshot

  class SaveSnapshotTestProcessor(name: String, probe: ActorRef) extends NamedProcessor(name) {
    var state = List.empty[String]
    def receive = {
      case Persistent(payload, snr)  ⇒ state = s"${payload}-${snr}" :: state
      case TakeSnapshot              ⇒ saveSnapshot(state)
      case SaveSnapshotSucceeded(md) ⇒ probe ! md.sequenceNr
      case GetState                  ⇒ probe ! state.reverse
    }
  }

  class LoadSnapshotTestProcessor(name: String, probe: ActorRef) extends NamedProcessor(name) {
    def receive = {
      case Persistent(payload, snr) ⇒ probe ! s"${payload}-${snr}"
      case SnapshotOffer(md, s)     ⇒ probe ! ((md, s))
      case other                    ⇒ probe ! other
    }
    override def preStart() = ()
  }
}

class SnapshotSpec extends AkkaSpec(SnapshotSpec.config) with PersistenceSpec with ImplicitSender {
  import SnapshotSpec._

  override protected def beforeEach() {
    super.beforeEach()

    val processor = system.actorOf(Props(classOf[SaveSnapshotTestProcessor], name, testActor))
    processor ! Persistent("a")
    processor ! TakeSnapshot
    processor ! Persistent("b")
    processor ! TakeSnapshot
    processor ! Persistent("c")
    processor ! Persistent("d")
    processor ! TakeSnapshot
    processor ! Persistent("e")
    processor ! Persistent("f")
    expectMsgAllOf(1L, 2L, 4L)
  }

  "A processor" must {
    "recover state starting from the most recent snapshot" in {
      val processor = system.actorOf(Props(classOf[LoadSnapshotTestProcessor], name, testActor))
      val processorId = name

      processor ! Recover()

      expectMsgPF() {
        case (SnapshotMetadata(`processorId`, 4, timestamp), state) ⇒ {
          state must be(List("a-1", "b-2", "c-3", "d-4").reverse)
          timestamp must be > (0L)
        }
      }
      expectMsg("e-5")
      expectMsg("f-6")
    }
    "recover state starting from the most recent snapshot matching an upper sequence number bound" in {
      val processor = system.actorOf(Props(classOf[LoadSnapshotTestProcessor], name, testActor))
      val processorId = name

      processor ! Recover(toSequenceNr = 3)

      expectMsgPF() {
        case (SnapshotMetadata(`processorId`, 2, timestamp), state) ⇒ {
          state must be(List("a-1", "b-2").reverse)
          timestamp must be > (0L)
        }
      }
      expectMsg("c-3")
    }
    "recover state starting from the most recent snapshot matching an upper sequence number bound (without further replay)" in {
      val processor = system.actorOf(Props(classOf[LoadSnapshotTestProcessor], name, testActor))
      val processorId = name

      processor ! Recover(toSequenceNr = 4)
      processor ! "done"

      expectMsgPF() {
        case (SnapshotMetadata(`processorId`, 4, timestamp), state) ⇒ {
          state must be(List("a-1", "b-2", "c-3", "d-4").reverse)
          timestamp must be > (0L)
        }
      }
      expectMsg("done")
    }
    "recover state starting from the most recent snapshot matching criteria" in {
      val processor = system.actorOf(Props(classOf[LoadSnapshotTestProcessor], name, testActor))
      val processorId = name

      processor ! Recover(fromSnapshot = SnapshotSelectionCriteria(maxSequenceNr = 2))

      expectMsgPF() {
        case (SnapshotMetadata(`processorId`, 2, timestamp), state) ⇒ {
          state must be(List("a-1", "b-2").reverse)
          timestamp must be > (0L)
        }
      }
      expectMsg("c-3")
      expectMsg("d-4")
      expectMsg("e-5")
      expectMsg("f-6")
    }
    "recover state starting from the most recent snapshot matching criteria and an upper sequence number bound" in {
      val processor = system.actorOf(Props(classOf[LoadSnapshotTestProcessor], name, testActor))
      val processorId = name

      processor ! Recover(fromSnapshot = SnapshotSelectionCriteria(maxSequenceNr = 2), toSequenceNr = 3)

      expectMsgPF() {
        case (SnapshotMetadata(`processorId`, 2, timestamp), state) ⇒ {
          state must be(List("a-1", "b-2").reverse)
          timestamp must be > (0L)
        }
      }
      expectMsg("c-3")
    }
    "recover state from scratch if snapshot based recovery is disabled" in {
      val processor = system.actorOf(Props(classOf[LoadSnapshotTestProcessor], name, testActor))

      processor ! Recover(fromSnapshot = SnapshotSelectionCriteria.None, toSequenceNr = 3)

      expectMsg("a-1")
      expectMsg("b-2")
      expectMsg("c-3")
    }
  }
}
