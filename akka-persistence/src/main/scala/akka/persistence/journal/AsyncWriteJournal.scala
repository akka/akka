/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 * Copyright (C) 2012-2013 Eligotech BV.
 */

package akka.persistence.journal

import scala.collection.immutable
import scala.concurrent.Future
import scala.util._

import akka.actor._
import akka.pattern.pipe
import akka.persistence._

/**
 * Abstract journal, optimized for asynchronous, non-blocking writes.
 */
trait AsyncWriteJournal extends Actor with WriteJournalBase with AsyncRecovery {
  import JournalProtocol._
  import AsyncWriteJournal._
  import context.dispatcher

  private val extension = Persistence(context.system)
  private val publish = extension.settings.internal.publishPluginCommands

  private val resequencer = context.actorOf(Props[Resequencer]())
  private var resequencerCounter = 1L

  def receive = {
    case WriteMessages(resequenceables, processor, actorInstanceId) ⇒
      val cctr = resequencerCounter
      def resequence(f: PersistentRepr ⇒ Any) = resequenceables.zipWithIndex.foreach {
        case (p: PersistentRepr, i) ⇒ resequencer ! Desequenced(f(p), cctr + i + 1, processor, p.sender)
        case (r, i)                 ⇒ resequencer ! Desequenced(LoopMessageSuccess(r.payload, actorInstanceId), cctr + i + 1, processor, r.sender)
      }
      asyncWriteMessages(preparePersistentBatch(resequenceables)) onComplete {
        case Success(_) ⇒
          resequencer ! Desequenced(WriteMessagesSuccessful, cctr, processor, self)
          resequence(WriteMessageSuccess(_, actorInstanceId))
        case Failure(e) ⇒
          resequencer ! Desequenced(WriteMessagesFailed(e), cctr, processor, self)
          resequence(WriteMessageFailure(_, e, actorInstanceId))
      }
      resequencerCounter += resequenceables.length + 1
    case r @ ReplayMessages(fromSequenceNr, toSequenceNr, max, persistenceId, processor, replayDeleted) ⇒
      // Send replayed messages and replay result to processor directly. No need
      // to resequence replayed messages relative to written and looped messages.
      asyncReplayMessages(persistenceId, fromSequenceNr, toSequenceNr, max) { p ⇒
        if (!p.deleted || replayDeleted) processor.tell(ReplayedMessage(p), p.sender)
      } map {
        case _ ⇒ ReplayMessagesSuccess
      } recover {
        case e ⇒ ReplayMessagesFailure(e)
      } pipeTo (processor) onSuccess {
        case _ if publish ⇒ context.system.eventStream.publish(r)
      }
    case ReadHighestSequenceNr(fromSequenceNr, persistenceId, processor) ⇒
      // Send read highest sequence number to processor directly. No need
      // to resequence the result relative to written and looped messages.
      asyncReadHighestSequenceNr(persistenceId, fromSequenceNr).map {
        highest ⇒ ReadHighestSequenceNrSuccess(highest)
      } recover {
        case e ⇒ ReadHighestSequenceNrFailure(e)
      } pipeTo (processor)
    case c @ WriteConfirmations(confirmationsBatch, requestor) ⇒
      asyncWriteConfirmations(confirmationsBatch) onComplete {
        case Success(_) ⇒ requestor ! WriteConfirmationsSuccess(confirmationsBatch)
        case Failure(e) ⇒ requestor ! WriteConfirmationsFailure(e)
      }
    case d @ DeleteMessages(messageIds, permanent, requestorOption) ⇒
      asyncDeleteMessages(messageIds, permanent) onComplete {
        case Success(_) ⇒
          requestorOption.foreach(_ ! DeleteMessagesSuccess(messageIds))
          if (publish) context.system.eventStream.publish(d)
        case Failure(e) ⇒
      }
    case d @ DeleteMessagesTo(persistenceId, toSequenceNr, permanent) ⇒
      asyncDeleteMessagesTo(persistenceId, toSequenceNr, permanent) onComplete {
        case Success(_) ⇒ if (publish) context.system.eventStream.publish(d)
        case Failure(e) ⇒
      }
    case LoopMessage(message, processor, actorInstanceId) ⇒
      resequencer ! Desequenced(LoopMessageSuccess(message, actorInstanceId), resequencerCounter, processor, sender)
      resequencerCounter += 1
  }

  //#journal-plugin-api
  /**
   * Plugin API: asynchronously writes a batch of persistent messages to the journal.
   * The batch write must be atomic i.e. either all persistent messages in the batch
   * are written or none.
   */
  def asyncWriteMessages(messages: immutable.Seq[PersistentRepr]): Future[Unit]

  /**
   * Plugin API: asynchronously writes a batch of delivery confirmations to the journal.
   */
  @deprecated("writeConfirmations will be removed, since Channels will be removed.", since = "2.3.4")
  def asyncWriteConfirmations(confirmations: immutable.Seq[PersistentConfirmation]): Future[Unit]

  /**
   * Plugin API: asynchronously deletes messages identified by `messageIds` from the
   * journal. If `permanent` is set to `false`, the persistent messages are marked as
   * deleted, otherwise they are permanently deleted.
   */
  @deprecated("asyncDeleteMessages will be removed.", since = "2.3.4")
  def asyncDeleteMessages(messageIds: immutable.Seq[PersistentId], permanent: Boolean): Future[Unit]

  /**
   * Plugin API: asynchronously deletes all persistent messages up to `toSequenceNr`
   * (inclusive). If `permanent` is set to `false`, the persistent messages are marked
   * as deleted, otherwise they are permanently deleted.
   */
  def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long, permanent: Boolean): Future[Unit]
  //#journal-plugin-api
}

/**
 * INTERNAL API.
 */
private[persistence] object AsyncWriteJournal {
  final case class Desequenced(msg: Any, snr: Long, target: ActorRef, sender: ActorRef)

  class Resequencer extends Actor {
    import scala.collection.mutable.Map

    private val delayed = Map.empty[Long, Desequenced]
    private var delivered = 0L

    def receive = {
      case d: Desequenced ⇒ resequence(d)
    }

    @scala.annotation.tailrec
    private def resequence(d: Desequenced) {
      if (d.snr == delivered + 1) {
        delivered = d.snr
        d.target tell (d.msg, d.sender)
      } else {
        delayed += (d.snr -> d)
      }
      val ro = delayed.remove(delivered + 1)
      if (ro.isDefined) resequence(ro.get)
    }
  }
}

