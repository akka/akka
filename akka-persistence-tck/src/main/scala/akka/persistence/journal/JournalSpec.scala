/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.persistence.journal

import akka.actor._
import akka.persistence.JournalProtocol._
import akka.persistence._
import akka.persistence.scalatest.{ MayVerb, OptionalTests }
import akka.testkit._
import com.typesafe.config._

import scala.concurrent.duration._

object JournalSpec {
  val config = ConfigFactory.parseString(
    """
    akka.persistence.publish-plugin-commands = on
    """)
}

/**
 * This spec aims to verify custom akka-persistence Journal implementations.
 * Plugin authors are highly encouraged to include it in their plugin's test suites.
 *
 * In case your journal plugin needs some kind of setup or teardown, override the `beforeAll` or `afterAll`
 * methods (don't forget to call `super` in your overridden methods).
 *
 * For a Java and JUnit consumable version of the TCK please refer to [[akka.persistence.japi.journal.JavaJournalSpec]].
 *
 * @see [[akka.persistence.journal.JournalPerfSpec]]
 * @see [[akka.persistence.japi.journal.JavaJournalPerfSpec]]
 */
abstract class JournalSpec(config: Config) extends PluginSpec(config) with MayVerb
  with OptionalTests with JournalCapabilityFlags {

  implicit lazy val system: ActorSystem = ActorSystem("JournalSpec", config.withFallback(JournalSpec.config))

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    preparePersistenceId(pid)
    writeMessages(1, 5, pid, senderProbe.ref, writerUuid)
  }

  "A journal" must {
    "replay all messages" in {
      journal ! ReplayMessages(1, Long.MaxValue, Long.MaxValue, pid, receiverProbe.ref)
      1 to 5 foreach { i ⇒ receiverProbe.expectMsg(replayedMessage(i)) }
      receiverProbe.expectMsg(RecoverySuccess(highestSequenceNr = 5L))
    }
    "replay messages using a lower sequence number bound" in {
      journal ! ReplayMessages(3, Long.MaxValue, Long.MaxValue, pid, receiverProbe.ref)
      3 to 5 foreach { i ⇒ receiverProbe.expectMsg(replayedMessage(i)) }
      receiverProbe.expectMsg(RecoverySuccess(highestSequenceNr = 5L))
    }
    "replay messages using an upper sequence number bound" in {
      journal ! ReplayMessages(1, 3, Long.MaxValue, pid, receiverProbe.ref)
      1 to 3 foreach { i ⇒ receiverProbe.expectMsg(replayedMessage(i)) }
      receiverProbe.expectMsg(RecoverySuccess(highestSequenceNr = 5L))
    }
    "replay messages using a count limit" in {
      journal ! ReplayMessages(1, Long.MaxValue, 3, pid, receiverProbe.ref)
      1 to 3 foreach { i ⇒ receiverProbe.expectMsg(replayedMessage(i)) }
      receiverProbe.expectMsg(RecoverySuccess(highestSequenceNr = 5L))
    }
    "replay messages using a lower and upper sequence number bound" in {
      journal ! ReplayMessages(2, 3, Long.MaxValue, pid, receiverProbe.ref)
      2 to 3 foreach { i ⇒ receiverProbe.expectMsg(replayedMessage(i)) }
      receiverProbe.expectMsg(RecoverySuccess(highestSequenceNr = 5L))
    }
    "replay messages using a lower and upper sequence number bound and a count limit" in {
      journal ! ReplayMessages(2, 5, 2, pid, receiverProbe.ref)
      2 to 3 foreach { i ⇒ receiverProbe.expectMsg(replayedMessage(i)) }
      receiverProbe.expectMsg(RecoverySuccess(highestSequenceNr = 5L))
    }
    "replay a single if lower sequence number bound equals upper sequence number bound" in {
      journal ! ReplayMessages(2, 2, Long.MaxValue, pid, receiverProbe.ref)
      2 to 2 foreach { i ⇒ receiverProbe.expectMsg(replayedMessage(i)) }
      receiverProbe.expectMsg(RecoverySuccess(highestSequenceNr = 5L))
    }
    "replay a single message if count limit equals 1" in {
      journal ! ReplayMessages(2, 4, 1, pid, receiverProbe.ref)
      2 to 2 foreach { i ⇒ receiverProbe.expectMsg(replayedMessage(i)) }
      receiverProbe.expectMsg(RecoverySuccess(highestSequenceNr = 5L))
    }
    "not replay messages if count limit equals 0" in {
      journal ! ReplayMessages(2, 4, 0, pid, receiverProbe.ref)
      receiverProbe.expectMsg(RecoverySuccess(highestSequenceNr = 5L))
    }
    "not replay messages if lower  sequence number bound is greater than upper sequence number bound" in {
      journal ! ReplayMessages(3, 2, Long.MaxValue, pid, receiverProbe.ref)
      receiverProbe.expectMsg(RecoverySuccess(highestSequenceNr = 5L))
    }
    "not replay messages if the persistent actor has not yet written messages" in {
      journal ! ReplayMessages(0, Long.MaxValue, Long.MaxValue, "non-existing-pid", receiverProbe.ref)
      receiverProbe.expectMsg(RecoverySuccess(highestSequenceNr = 0L))
    }
    "not replay permanently deleted messages (range deletion)" in {
      val receiverProbe2 = TestProbe()
      val cmd = DeleteMessagesTo(pid, 3, receiverProbe2.ref)
      val sub = TestProbe()

      subscribe[DeleteMessagesTo](sub.ref)
      journal ! cmd
      sub.expectMsg(cmd)
      receiverProbe2.expectMsg(DeleteMessagesSuccess(cmd.toSequenceNr))

      journal ! ReplayMessages(1, Long.MaxValue, Long.MaxValue, pid, receiverProbe.ref)
      List(4, 5) foreach { i ⇒ receiverProbe.expectMsg(replayedMessage(i)) }

      receiverProbe2.expectNoMsg(200.millis)
    }

    "not reset highestSequenceNr after message deletion" in {
      journal ! ReplayMessages(0, Long.MaxValue, Long.MaxValue, pid, receiverProbe.ref)
      1 to 5 foreach { i ⇒ receiverProbe.expectMsg(replayedMessage(i)) }
      receiverProbe.expectMsg(RecoverySuccess(highestSequenceNr = 5L))

      journal ! DeleteMessagesTo(pid, 3L, receiverProbe.ref)
      receiverProbe.expectMsg(DeleteMessagesSuccess(3L))

      journal ! ReplayMessages(0, Long.MaxValue, Long.MaxValue, pid, receiverProbe.ref)
      4 to 5 foreach { i ⇒ receiverProbe.expectMsg(replayedMessage(i)) }
      receiverProbe.expectMsg(RecoverySuccess(highestSequenceNr = 5L))
    }

    "not reset highestSequenceNr after journal cleanup" in {
      journal ! ReplayMessages(0, Long.MaxValue, Long.MaxValue, pid, receiverProbe.ref)
      1 to 5 foreach { i ⇒ receiverProbe.expectMsg(replayedMessage(i)) }
      receiverProbe.expectMsg(RecoverySuccess(highestSequenceNr = 5L))

      journal ! DeleteMessagesTo(pid, Long.MaxValue, receiverProbe.ref)
      receiverProbe.expectMsg(DeleteMessagesSuccess(Long.MaxValue))

      journal ! ReplayMessages(0, Long.MaxValue, Long.MaxValue, pid, receiverProbe.ref)
      receiverProbe.expectMsg(RecoverySuccess(highestSequenceNr = 5L))
    }
  }

  "A Journal optionally" may {

    optional(flag = supportsRejectingNonSerializableObjects) {
      "reject non-serializable events" in EventFilter[java.io.NotSerializableException]().intercept {
        // there is no chance that a journal could create a data representation for type of event
        val notSerializableEvent = new Object {
          override def toString = "not serializable"
        }
        val msgs = (6 to 8).map { i ⇒
          val event = if (i == 7) notSerializableEvent else s"b-$i"
          AtomicWrite(PersistentRepr(payload = event, sequenceNr = i, persistenceId = pid, sender = Actor.noSender,
            writerUuid = writerUuid))
        }

        val probe = TestProbe()
        journal ! WriteMessages(msgs, probe.ref, actorInstanceId)

        probe.expectMsg(WriteMessagesSuccessful)
        val Pid = pid
        val WriterUuid = writerUuid
        probe.expectMsgPF() {
          case WriteMessageSuccess(PersistentImpl(payload, 6L, Pid, _, _, Actor.noSender, WriterUuid), _) ⇒ payload should be(s"b-6")
        }
        probe.expectMsgPF() {
          case WriteMessageRejected(PersistentImpl(payload, 7L, Pid, _, _, Actor.noSender, WriterUuid), _, _) ⇒
            payload should be(notSerializableEvent)
        }
        probe.expectMsgPF() {
          case WriteMessageSuccess(PersistentImpl(payload, 8L, Pid, _, _, Actor.noSender, WriterUuid), _) ⇒ payload should be(s"b-8")
        }
      }
    }
  }
}
