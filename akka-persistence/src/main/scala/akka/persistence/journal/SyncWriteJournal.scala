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
    case WriteMessages(messages, persistentActor, actorInstanceId) ⇒
      Try(writeMessages(preparePersistentBatch(messages))) match {
        case Success(_) ⇒
          persistentActor ! WriteMessagesSuccessful
          messages.foreach {
            case p: PersistentRepr ⇒ persistentActor.tell(WriteMessageSuccess(p, actorInstanceId), p.sender)
            case r                 ⇒ persistentActor.tell(LoopMessageSuccess(r.payload, actorInstanceId), r.sender)
          }
        case Failure(e) ⇒
          persistentActor ! WriteMessagesFailed(e)
          messages.foreach {
            case p: PersistentRepr ⇒ persistentActor.tell(WriteMessageFailure(p, e, actorInstanceId), p.sender)
            case r                 ⇒ persistentActor.tell(LoopMessageSuccess(r.payload, actorInstanceId), r.sender)
          }
          throw e
      }
    case r @ ReplayMessages(fromSequenceNr, toSequenceNr, max, persistenceId, persistentActor, replayDeleted) ⇒
      asyncReplayMessages(persistenceId, fromSequenceNr, toSequenceNr, max) { p ⇒
        if (!p.deleted || replayDeleted) persistentActor.tell(ReplayedMessage(p), p.sender)
      } map {
        case _ ⇒ ReplayMessagesSuccess
      } recover {
        case e ⇒ ReplayMessagesFailure(e)
      } pipeTo (persistentActor) onSuccess {
        case _ if publish ⇒ context.system.eventStream.publish(r)
      }
    case ReadHighestSequenceNr(fromSequenceNr, persistenceId, persistentActor) ⇒
      asyncReadHighestSequenceNr(persistenceId, fromSequenceNr).map {
        highest ⇒ ReadHighestSequenceNrSuccess(highest)
      } recover {
        case e ⇒ ReadHighestSequenceNrFailure(e)
      } pipeTo (persistentActor)
    case d @ DeleteMessagesTo(persistenceId, toSequenceNr, permanent) ⇒
      Try(deleteMessagesTo(persistenceId, toSequenceNr, permanent)) match {
        case Success(_) ⇒ if (publish) context.system.eventStream.publish(d)
        case Failure(e) ⇒
      }
  }

  //#journal-plugin-api
  /**
   * Plugin API: synchronously writes a batch of persistent messages to the journal.
   * The batch write must be atomic i.e. either all persistent messages in the batch
   * are written or none.
   */
  def writeMessages(messages: immutable.Seq[PersistentRepr]): Unit

  /**
   * Plugin API: synchronously deletes all persistent messages up to `toSequenceNr`
   * (inclusive). If `permanent` is set to `false`, the persistent messages are marked
   * as deleted, otherwise they are permanently deleted.
   */
  def deleteMessagesTo(persistenceId: String, toSequenceNr: Long, permanent: Boolean): Unit
  //#journal-plugin-api
}
