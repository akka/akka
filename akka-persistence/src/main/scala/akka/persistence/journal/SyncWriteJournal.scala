/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 * Copyright (C) 2012-2013 Eligotech BV.
 */

package akka.persistence.journal

import scala.collection.immutable
import scala.util._

import akka.actor.Actor
import akka.pattern.pipe
import akka.persistence._

/**
 * Abstract journal, optimized for synchronous writes.
 */
trait SyncWriteJournal extends Actor with WriteJournalBase with AsyncRecovery {
  import JournalProtocol._
  import context.dispatcher

  private val extension = Persistence(context.system)
  private val publish = extension.settings.internal.publishPluginCommands

  final def receive = {
    case WriteMessages(resequenceables, processor, actorInstanceId) ⇒
      Try(writeMessages(preparePersistentBatch(resequenceables))) match {
        case Success(_) ⇒
          processor ! WriteMessagesSuccessful
          resequenceables.foreach {
            case p: PersistentRepr ⇒ processor.tell(WriteMessageSuccess(p, actorInstanceId), p.sender)
            case r                 ⇒ processor.tell(LoopMessageSuccess(r.payload, actorInstanceId), r.sender)
          }
        case Failure(e) ⇒
          processor ! WriteMessagesFailed(e)
          resequenceables.foreach {
            case p: PersistentRepr ⇒ processor tell (WriteMessageFailure(p, e, actorInstanceId), p.sender)
            case r                 ⇒ processor tell (LoopMessageSuccess(r.payload, actorInstanceId), r.sender)
          }
          throw e
      }
    case r @ ReplayMessages(fromSequenceNr, toSequenceNr, max, persistenceId, processor, replayDeleted) ⇒
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
      asyncReadHighestSequenceNr(persistenceId, fromSequenceNr).map {
        highest ⇒ ReadHighestSequenceNrSuccess(highest)
      } recover {
        case e ⇒ ReadHighestSequenceNrFailure(e)
      } pipeTo (processor)
    case WriteConfirmations(confirmationsBatch, requestor) ⇒
      Try(writeConfirmations(confirmationsBatch)) match {
        case Success(_) ⇒ requestor ! WriteConfirmationsSuccess(confirmationsBatch)
        case Failure(e) ⇒ requestor ! WriteConfirmationsFailure(e)
      }
    case d @ DeleteMessages(messageIds, permanent, requestorOption) ⇒
      Try(deleteMessages(messageIds, permanent)) match {
        case Success(_) ⇒
          requestorOption.foreach(_ ! DeleteMessagesSuccess(messageIds))
          if (publish) context.system.eventStream.publish(d)
        case Failure(e) ⇒
          requestorOption.foreach(_ ! DeleteMessagesFailure(e))
      }
    case d @ DeleteMessagesTo(persistenceId, toSequenceNr, permanent) ⇒
      Try(deleteMessagesTo(persistenceId, toSequenceNr, permanent)) match {
        case Success(_) ⇒ if (publish) context.system.eventStream.publish(d)
        case Failure(e) ⇒
      }
    case LoopMessage(message, processor, actorInstanceId) ⇒
      processor forward LoopMessageSuccess(message, actorInstanceId)
  }

  //#journal-plugin-api
  /**
   * Plugin API: synchronously writes a batch of persistent messages to the journal.
   * The batch write must be atomic i.e. either all persistent messages in the batch
   * are written or none.
   */
  def writeMessages(messages: immutable.Seq[PersistentRepr]): Unit

  /**
   * Plugin API: synchronously writes a batch of delivery confirmations to the journal.
   */
  @deprecated("writeConfirmations will be removed, since Channels will be removed.", since = "2.3.4")
  def writeConfirmations(confirmations: immutable.Seq[PersistentConfirmation]): Unit

  /**
   * Plugin API: synchronously deletes messages identified by `messageIds` from the
   * journal. If `permanent` is set to `false`, the persistent messages are marked as
   * deleted, otherwise they are permanently deleted.
   */
  @deprecated("deleteMessages will be removed.", since = "2.3.4")
  def deleteMessages(messageIds: immutable.Seq[PersistentId], permanent: Boolean): Unit

  /**
   * Plugin API: synchronously deletes all persistent messages up to `toSequenceNr`
   * (inclusive). If `permanent` is set to `false`, the persistent messages are marked
   * as deleted, otherwise they are permanently deleted.
   */
  def deleteMessagesTo(persistenceId: String, toSequenceNr: Long, permanent: Boolean): Unit
  //#journal-plugin-api
}
