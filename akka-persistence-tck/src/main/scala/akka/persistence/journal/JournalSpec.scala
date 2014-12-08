package akka.persistence.journal

import scala.collection.immutable.Seq

import akka.actor._
import akka.persistence._
import akka.persistence.JournalProtocol._
import akka.testkit._

import com.typesafe.config._

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
 * methods (don't forget to call `super` in your overriden methods).
 *
 * For a Java and JUnit consumable version of the TCK please refer to [[akka.persistence.japi.journal.JavaJournalSpec]].
 *
 * @see [[akka.persistence.journal.JournalPerfSpec]]
 * @see [[akka.persistence.japi.journal.JavaJournalPerfSpec]]
 */
trait JournalSpec extends PluginSpec {
  import JournalSpec._

  implicit lazy val system: ActorSystem = ActorSystem("JournalSpec", config.withFallback(JournalSpec.config))

  private var senderProbe: TestProbe = _
  private var receiverProbe: TestProbe = _

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    senderProbe = TestProbe()
    receiverProbe = TestProbe()
    writeMessages(1, 5, pid, senderProbe.ref)
  }

  def journal: ActorRef =
    extension.journalFor(null)

  def replayedMessage(snr: Long, deleted: Boolean = false, confirms: Seq[String] = Nil): ReplayedMessage =
    ReplayedMessage(PersistentImpl(s"a-${snr}", snr, pid, deleted, senderProbe.ref))

  def writeMessages(from: Int, to: Int, pid: String, sender: ActorRef): Unit = {
    val msgs = from to to map { i ⇒ PersistentRepr(payload = s"a-${i}", sequenceNr = i, persistenceId = pid, sender = sender) }
    val probe = TestProbe()

    journal ! WriteMessages(msgs, probe.ref, actorInstanceId)

    probe.expectMsg(WriteMessagesSuccessful)
    from to to foreach { i ⇒
      probe.expectMsgPF() { case WriteMessageSuccess(PersistentImpl(payload, `i`, `pid`, _, `sender`), _) ⇒ payload should be(s"a-${i}") }
    }
  }

  "A journal" must {
    "replay all messages" in {
      journal ! ReplayMessages(1, Long.MaxValue, Long.MaxValue, pid, receiverProbe.ref)
      1 to 5 foreach { i ⇒ receiverProbe.expectMsg(replayedMessage(i)) }
      receiverProbe.expectMsg(ReplayMessagesSuccess)
    }
    "replay messages using a lower sequence number bound" in {
      journal ! ReplayMessages(3, Long.MaxValue, Long.MaxValue, pid, receiverProbe.ref)
      3 to 5 foreach { i ⇒ receiverProbe.expectMsg(replayedMessage(i)) }
      receiverProbe.expectMsg(ReplayMessagesSuccess)
    }
    "replay messages using an upper sequence number bound" in {
      journal ! ReplayMessages(1, 3, Long.MaxValue, pid, receiverProbe.ref)
      1 to 3 foreach { i ⇒ receiverProbe.expectMsg(replayedMessage(i)) }
      receiverProbe.expectMsg(ReplayMessagesSuccess)
    }
    "replay messages using a count limit" in {
      journal ! ReplayMessages(1, Long.MaxValue, 3, pid, receiverProbe.ref)
      1 to 3 foreach { i ⇒ receiverProbe.expectMsg(replayedMessage(i)) }
      receiverProbe.expectMsg(ReplayMessagesSuccess)
    }
    "replay messages using a lower and upper sequence number bound" in {
      journal ! ReplayMessages(2, 4, Long.MaxValue, pid, receiverProbe.ref)
      2 to 4 foreach { i ⇒ receiverProbe.expectMsg(replayedMessage(i)) }
      receiverProbe.expectMsg(ReplayMessagesSuccess)
    }
    "replay messages using a lower and upper sequence number bound and a count limit" in {
      journal ! ReplayMessages(2, 4, 2, pid, receiverProbe.ref)
      2 to 3 foreach { i ⇒ receiverProbe.expectMsg(replayedMessage(i)) }
      receiverProbe.expectMsg(ReplayMessagesSuccess)
    }
    "replay a single if lower sequence number bound equals upper sequence number bound" in {
      journal ! ReplayMessages(2, 2, Long.MaxValue, pid, receiverProbe.ref)
      2 to 2 foreach { i ⇒ receiverProbe.expectMsg(replayedMessage(i)) }
      receiverProbe.expectMsg(ReplayMessagesSuccess)
    }
    "replay a single message if count limit equals 1" in {
      journal ! ReplayMessages(2, 4, 1, pid, receiverProbe.ref)
      2 to 2 foreach { i ⇒ receiverProbe.expectMsg(replayedMessage(i)) }
      receiverProbe.expectMsg(ReplayMessagesSuccess)
    }
    "not replay messages if count limit equals 0" in {
      journal ! ReplayMessages(2, 4, 0, pid, receiverProbe.ref)
      receiverProbe.expectMsg(ReplayMessagesSuccess)
    }
    "not replay messages if lower  sequence number bound is greater than upper sequence number bound" in {
      journal ! ReplayMessages(3, 2, Long.MaxValue, pid, receiverProbe.ref)
      receiverProbe.expectMsg(ReplayMessagesSuccess)
    }
    "not replay permanently deleted messages (range deletion)" in {
      val cmd = DeleteMessagesTo(pid, 3, true)
      val sub = TestProbe()

      subscribe[DeleteMessagesTo](sub.ref)
      journal ! cmd
      sub.expectMsg(cmd)

      journal ! ReplayMessages(1, Long.MaxValue, Long.MaxValue, pid, receiverProbe.ref)
      List(4, 5) foreach { i ⇒ receiverProbe.expectMsg(replayedMessage(i)) }
    }
    "replay logically deleted messages with deleted field set to true (range deletion)" in {
      val cmd = DeleteMessagesTo(pid, 3, false)
      val sub = TestProbe()

      subscribe[DeleteMessagesTo](sub.ref)
      journal ! cmd
      sub.expectMsg(cmd)

      journal ! ReplayMessages(1, Long.MaxValue, Long.MaxValue, pid, receiverProbe.ref, replayDeleted = true)
      1 to 5 foreach { i ⇒
        i match {
          case 1 | 2 | 3 ⇒ receiverProbe.expectMsg(replayedMessage(i, deleted = true))
          case 4 | 5     ⇒ receiverProbe.expectMsg(replayedMessage(i))
        }
      }
    }

    "return a highest stored sequence number > 0 if the persistent actor has already written messages and the message log is non-empty" in {
      journal ! ReadHighestSequenceNr(3L, pid, receiverProbe.ref)
      receiverProbe.expectMsg(ReadHighestSequenceNrSuccess(5))

      journal ! ReadHighestSequenceNr(5L, pid, receiverProbe.ref)
      receiverProbe.expectMsg(ReadHighestSequenceNrSuccess(5))
    }
    "return a highest stored sequence number == 0 if the persistent actor has not yet written messages" in {
      journal ! ReadHighestSequenceNr(0L, "non-existing-pid", receiverProbe.ref)
      receiverProbe.expectMsg(ReadHighestSequenceNrSuccess(0))
    }
  }
}
