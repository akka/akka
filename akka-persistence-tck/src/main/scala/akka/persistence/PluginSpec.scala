/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.persistence

import java.util.concurrent.atomic.AtomicInteger

import scala.reflect.ClassTag
import akka.actor._
import akka.testkit._
import com.typesafe.config._
import org.scalatest._
import java.util.UUID

import akka.persistence.JournalProtocol._
import akka.persistence.journal.Tagged

import scala.collection.immutable.Seq

abstract class PluginSpec(val config: Config) extends TestKitBase with WordSpecLike with Matchers with BeforeAndAfterAll with BeforeAndAfterEach {
  private val counter = new AtomicInteger(0)

  protected var senderProbe: TestProbe = _
  protected var receiverProbe: TestProbe = _

  private var _extension: Persistence = _
  private var _pid: String = _
  private var _writerUuid: String = _

  // used to avoid messages be delivered to a restarted actor,
  // this is akka-persistence internals and journals themselves don't really care
  protected val actorInstanceId = 1

  override protected def beforeEach(): Unit = {
    _pid = nextPid
    _writerUuid = UUID.randomUUID.toString
    senderProbe = TestProbe()
    receiverProbe = TestProbe()
  }

  override protected def beforeAll(): Unit =
    _extension = Persistence(system)

  override protected def afterAll(): Unit =
    shutdown(system)

  def extension: Persistence = _extension

  /**
   * Returns a new unique persistence id
   */
  def nextPid: String =
    s"p-${counter.incrementAndGet()}"

  /**
   * Returns the current persistence id
   */
  def pid: String = _pid

  /**
   * Returns the current writer uuid
   */
  def writerUuid: String = _writerUuid

  /**
   * Returns the actorRef of the default configured journal plugin
   */
  def journal: ActorRef =
    extension.journalFor(null)

  def subscribe[T: ClassTag](subscriber: ActorRef) =
    system.eventStream.subscribe(subscriber, implicitly[ClassTag[T]].runtimeClass)

  /**
   * Overridable hook that is called before populating the journal for the next
   * test case. `pid` is the `persistenceId` that will be used in the test.
   * This method may be needed to clean pre-existing events from the log.
   */
  def preparePersistenceId(pid: String): Unit = ()

  /**
   * Implementation may override and return false if it does not
   * support atomic writes of several events, as emitted by `persistAll`.
   */
  def supportsAtomicPersistAllOfSeveralEvents: Boolean = true

  def replayedMessage(snr: Long, deleted: Boolean = false, confirms: Seq[String] = Nil): ReplayedMessage =
    ReplayedMessage(PersistentImpl(s"a-$snr", snr, pid, "", deleted, Actor.noSender, writerUuid))

  def writeMessages(fromSnr: Int, toSnr: Int, pid: String, sender: ActorRef, writerUuid: String, tags: String*): Unit = {
    def persistentRepr(sequenceNr: Long) =
      PersistentRepr(
        payload = if (tags.isEmpty) s"a-$sequenceNr" else Tagged(s"a-$sequenceNr", Set(tags: _*)),
        sequenceNr = sequenceNr,
        persistenceId = pid,
        sender = sender,
        writerUuid = writerUuid
      )

    val msgs: Seq[PersistentEnvelope] =
      if (supportsAtomicPersistAllOfSeveralEvents)
        Seq(AtomicWrite((fromSnr to toSnr).map(i ⇒ persistentRepr(i))))
      else (fromSnr to toSnr).map(i ⇒ AtomicWrite(persistentRepr(i)))

    val probe = TestProbe()

    journal ! WriteMessages(msgs, probe.ref, actorInstanceId)

    probe.expectMsg(WriteMessagesSuccessful)
    fromSnr to toSnr foreach { seqNo ⇒
      probe.expectMsgPF() {
        case WriteMessageSuccess(PersistentImpl(payload, `seqNo`, `pid`, _, _, `sender`, `writerUuid`), _) ⇒
          val id = s"a-$seqNo"
          payload should matchPattern {
            case `id`            ⇒
            case Tagged(`id`, _) ⇒
          }
      }
    }
  }

  /**
   * Deletes messages from the journal.
   */
  def delete(persistenceId: String, toSequenceNr: Long = Long.MaxValue): Unit = {
    val probe = TestProbe()
    journal ! DeleteMessagesTo(persistenceId, toSequenceNr, probe.ref)
    probe.expectMsgPF() {
      case DeleteMessagesSuccess(_) ⇒
    }
  }
}
