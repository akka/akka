/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 * Copyright (C) 2012-2013 Eligotech BV.
 */

package akka.persistence.journal

import scala.collection.immutable
import scala.util._
import akka.actor.{ ActorLogging, Actor }
import akka.pattern.pipe
import akka.persistence._

object SyncWriteJournal {
  val successUnit: Success[Unit] = Success(())
}

/**
 * Abstract journal, optimized for synchronous writes.
 */
trait SyncWriteJournal extends Actor with WriteJournalBase with AsyncRecovery with ActorLogging {
  import JournalProtocol._
  import context.dispatcher

  private val extension = Persistence(context.system)
  private val publish = extension.settings.internal.publishPluginCommands

  final def receive = {
    case WriteMessages(messages, persistentActor, actorInstanceId) ⇒
      val writeResult = Try {
        val prepared = preparePersistentBatch(messages)
        val results = writeMessages(prepared)
        if (results.size != prepared.size)
          throw new IllegalStateException("writeMessages returned invalid number of results. " +
            s"Expected [${prepared.size}], but got [${results.size}]")
        results
      }
      writeResult match {
        case Success(results) ⇒
          persistentActor ! WriteMessagesSuccessful
          val resultsIter = results.iterator
          messages.foreach {
            case a: AtomicWrite ⇒
              resultsIter.next() match {
                case Success(_) ⇒
                  a.payload.foreach { p ⇒
                    persistentActor.tell(WriteMessageSuccess(p, actorInstanceId), p.sender)
                  }
                case Failure(e) ⇒
                  a.payload.foreach { p ⇒
                    persistentActor.tell(WriteMessageRejected(p, e, actorInstanceId), p.sender)
                  }
              }

            case r: NonPersistentRepr ⇒
              persistentActor.tell(LoopMessageSuccess(r.payload, actorInstanceId), r.sender)
          }

        case Failure(e) ⇒
          persistentActor ! WriteMessagesFailed(e)
          messages.foreach {
            case a: AtomicWrite ⇒
              a.payload.foreach { p ⇒
                persistentActor.tell(WriteMessageFailure(p, e, actorInstanceId), p.sender)
              }
            case r: NonPersistentRepr ⇒
              persistentActor.tell(LoopMessageSuccess(r.payload, actorInstanceId), r.sender)
          }
          throw e
      }

    case r @ ReplayMessages(fromSequenceNr, toSequenceNr, max, persistenceId, persistentActor) ⇒
      asyncReplayMessages(persistenceId, fromSequenceNr, toSequenceNr, max) { p ⇒
        if (!p.deleted) // old records from 2.3 may still have the deleted flag
          adaptFromJournal(p).foreach { adaptedPersistentRepr ⇒
            persistentActor.tell(ReplayedMessage(adaptedPersistentRepr), adaptedPersistentRepr.sender)
          }
      } map {
        case _ ⇒ ReplayMessagesSuccess
      } recover {
        case e ⇒ ReplayMessagesFailure(e)
      } pipeTo persistentActor onSuccess {
        case _ if publish ⇒ context.system.eventStream.publish(r)
      }

    case ReadHighestSequenceNr(fromSequenceNr, persistenceId, persistentActor) ⇒
      asyncReadHighestSequenceNr(persistenceId, fromSequenceNr).map {
        highest ⇒ ReadHighestSequenceNrSuccess(highest)
      } recover {
        case e ⇒ ReadHighestSequenceNrFailure(e)
      } pipeTo persistentActor

    case d @ DeleteMessagesTo(persistenceId, toSequenceNr) ⇒
      Try(deleteMessagesTo(persistenceId, toSequenceNr)) match {
        case Success(_) ⇒ if (publish) context.system.eventStream.publish(d)
        case Failure(e) ⇒
      }
  }

  //#journal-plugin-api
  /**
   * * Plugin API: asynchronously writes a batch (`Seq`) of persistent messages to the journal.
   *
   * The batch is only for performance reasons, i.e. all messages don't have to be written
   * atomically. Higher throughput can typically be achieved by using batch inserts of many
   * records compared inserting records one-by-one, but this aspect depends on the underlying
   * data store and a journal implementation can implement it as efficient as possible with
   * the assumption that the messages of the batch are unrelated.
   *
   * Each `AtomicWrite` message contains the single `PersistentRepr` that corresponds to the
   * event that was passed to the `persist` method of the `PersistentActor`, or it contains
   * several `PersistentRepr` that corresponds to the events that were passed to the `persistAll`
   * method of the `PersistentActor`. All `PersistentRepr` of the `AtomicWrite` must be
   * written to the data store atomically, i.e. all or none must be stored.
   * If the journal (data store) cannot support atomic writes of multiple events it should
   * reject such writes with a `Try` `Failure` with an `UnsupportedOperationException`
   * describing the issue. This limitation should also be documented by the journal plugin.
   *
   * If there are failures when storing any of the messages in the batch the method must
   * throw an exception. The method must only return normally when all messages in the
   * batch have been confirmed to be stored successfully, i.e. they will be readable,
   * and visible, in a subsequent replay. If there are uncertainty about if the
   * messages were stored or not the method must throw an exception.
   *
   * Data store connection problems must be signaled by throwing an exception.
   *
   * The journal can also signal that it rejects individual messages (`AtomicWrite`) by
   * the returned `immutable.Seq[Try[Unit]]`. The returned `Seq` must have as many elements
   * as the input `messages` `Seq`. Each `Try` element signals if the corresponding `AtomicWrite`
   * is rejected or not, with an exception describing the problem. Rejecting a message means it
   * was not stored, i.e. it must not be included in a later replay. Rejecting a message is
   * typically done before attempting to store it, e.g. because of serialization error.
   *
   * Data store connection problems must not be signaled as rejections.
   */
  def writeMessages(messages: immutable.Seq[AtomicWrite]): immutable.Seq[Try[Unit]]

  /**
   * Plugin API: synchronously deletes all persistent messages up to `toSequenceNr`
   * (inclusive).
   */
  def deleteMessagesTo(persistenceId: String, toSequenceNr: Long): Unit
  //#journal-plugin-api
}
