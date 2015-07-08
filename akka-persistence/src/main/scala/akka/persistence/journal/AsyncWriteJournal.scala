/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 * Copyright (C) 2012-2013 Eligotech BV.
 */

package akka.persistence.journal

import akka.actor._
import akka.pattern.pipe
import akka.persistence._

import scala.collection.immutable
import scala.concurrent.Future
import scala.util.{ Failure, Success, Try }
import scala.util.control.NonFatal

/**
 * Abstract journal, optimized for asynchronous, non-blocking writes.
 */
trait AsyncWriteJournal extends Actor with WriteJournalBase with AsyncRecovery {
  import AsyncWriteJournal._
  import JournalProtocol._
  import context.dispatcher

  private val extension = Persistence(context.system)
  private val publish = extension.settings.internal.publishPluginCommands

  private val resequencer = context.actorOf(Props[Resequencer]())
  private var resequencerCounter = 1L

  final def receive = receiveWriteJournal.orElse[Any, Unit](receivePluginInternal)

  final val receiveWriteJournal: Actor.Receive = {
    case WriteMessages(messages, persistentActor, actorInstanceId) ⇒
      val cctr = resequencerCounter
      resequencerCounter += messages.foldLeft(0)((acc, m) ⇒ acc + m.size) + 1

      val prepared = Try(preparePersistentBatch(messages))
      val writeResult = (prepared match {
        case Success(prep) ⇒
          // in case the asyncWriteMessages throws
          try asyncWriteMessages(prep) catch { case NonFatal(e) ⇒ Future.failed(e) }
        case f @ Failure(_) ⇒
          // exception from preparePersistentBatch => rejected
          Future.successful(messages.collect { case a: AtomicWrite ⇒ f })
      }).map { results ⇒
        if (results.size != prepared.get.size)
          throw new IllegalStateException("asyncWriteMessages returned invalid number of results. " +
            s"Expected [${prepared.get.size}], but got [${results.size}]")
        results
      }

      writeResult.onComplete {
        case Success(results) ⇒
          resequencer ! Desequenced(WriteMessagesSuccessful, cctr, persistentActor, self)

          val resultsIter = results.iterator
          var n = cctr + 1
          messages.foreach {
            case a: AtomicWrite ⇒
              resultsIter.next() match {
                case Success(_) ⇒
                  a.payload.foreach { p ⇒
                    resequencer ! Desequenced(WriteMessageSuccess(p, actorInstanceId), n, persistentActor, p.sender)
                    n += 1
                  }
                case Failure(e) ⇒
                  a.payload.foreach { p ⇒
                    resequencer ! Desequenced(WriteMessageRejected(p, e, actorInstanceId), n, persistentActor, p.sender)
                    n += 1
                  }
              }

            case r: NonPersistentRepr ⇒
              resequencer ! Desequenced(LoopMessageSuccess(r.payload, actorInstanceId), n, persistentActor, r.sender)
              n += 1
          }

        case Failure(e) ⇒
          resequencer ! Desequenced(WriteMessagesFailed(e), cctr, persistentActor, self)
          var n = cctr + 1
          messages.foreach {
            case a: AtomicWrite ⇒
              a.payload.foreach { p ⇒
                resequencer ! Desequenced(WriteMessageFailure(p, e, actorInstanceId), n, persistentActor, p.sender)
                n += 1
              }
            case r: NonPersistentRepr ⇒
              resequencer ! Desequenced(LoopMessageSuccess(r.payload, actorInstanceId), n, persistentActor, r.sender)
              n += 1
          }
      }

    case r @ ReplayMessages(fromSequenceNr, toSequenceNr, max, persistenceId, persistentActor) ⇒

      asyncReadHighestSequenceNr(persistenceId, fromSequenceNr).flatMap { highSeqNr ⇒
        val toSeqNr = math.min(toSequenceNr, highSeqNr)
        if (highSeqNr == 0L || fromSequenceNr > toSeqNr)
          Future.successful(highSeqNr)
        else {
          // Send replayed messages and replay result to persistentActor directly. No need
          // to resequence replayed messages relative to written and looped messages.
          asyncReplayMessages(persistenceId, fromSequenceNr, toSeqNr, max) { p ⇒
            if (!p.deleted) // old records from 2.3 may still have the deleted flag
              adaptFromJournal(p).foreach { adaptedPersistentRepr ⇒
                persistentActor.tell(ReplayedMessage(adaptedPersistentRepr), Actor.noSender)
              }
          }.map(_ ⇒ highSeqNr)
        }
      }.map {
        highSeqNr ⇒ RecoverySuccess(highSeqNr)
      }.recover {
        case e ⇒ ReplayMessagesFailure(e)
      }.pipeTo(persistentActor).onSuccess {
        case _ if publish ⇒ context.system.eventStream.publish(r)
      }

    case d @ DeleteMessagesTo(persistenceId, toSequenceNr, persistentActor) ⇒
      asyncDeleteMessagesTo(persistenceId, toSequenceNr) map {
        case _ ⇒ DeleteMessagesSuccess(toSequenceNr)
      } recover {
        case e ⇒ DeleteMessagesFailure(e, toSequenceNr)
      } pipeTo persistentActor onComplete {
        case _ if publish ⇒ context.system.eventStream.publish(d)
      }
  }

  //#journal-plugin-api
  /**
   * Plugin API: asynchronously writes a batch (`Seq`) of persistent messages to the
   * journal.
   *
   * The batch is only for performance reasons, i.e. all messages don't have to be written
   * atomically. Higher throughput can typically be achieved by using batch inserts of many
   * records compared inserting records one-by-one, but this aspect depends on the
   * underlying data store and a journal implementation can implement it as efficient as
   * possible with the assumption that the messages of the batch are unrelated.
   *
   * Each `AtomicWrite` message contains the single `PersistentRepr` that corresponds to
   * the event that was passed to the `persist` method of the `PersistentActor`, or it
   * contains several `PersistentRepr` that corresponds to the events that were passed
   * to the `persistAll` method of the `PersistentActor`. All `PersistentRepr` of the
   * `AtomicWrite` must be written to the data store atomically, i.e. all or none must
   * be stored. If the journal (data store) cannot support atomic writes of multiple
   * events it should reject such writes with a `Try` `Failure` with an
   * `UnsupportedOperationException` describing the issue. This limitation should
   * also be documented by the journal plugin.
   *
   * If there are failures when storing any of the messages in the batch the returned
   * `Future` must be completed with failure. The `Future` must only be completed with
   * success when all messages in the batch have been confirmed to be stored successfully,
   * i.e. they will be readable, and visible, in a subsequent replay. If there is
   * uncertainty about if the messages were stored or not the `Future` must be completed
   * with failure.
   *
   * Data store connection problems must be signaled by completing the `Future` with
   * failure.
   *
   * The journal can also signal that it rejects individual messages (`AtomicWrite`) by
   * the returned `immutable.Seq[Try[Unit]]`. The returned `Seq` must have as many elements
   * as the input `messages` `Seq`. Each `Try` element signals if the corresponding
   * `AtomicWrite` is rejected or not, with an exception describing the problem. Rejecting
   * a message means it was not stored, i.e. it must not be included in a later replay.
   * Rejecting a message is typically done before attempting to store it, e.g. because of
   * serialization error.
   *
   * Data store connection problems must not be signaled as rejections.
   *
   * Note that it is possible to reduce number of allocations by
   * caching some result `Seq` for the happy path, i.e. when no messages are rejected.
   */
  def asyncWriteMessages(messages: immutable.Seq[AtomicWrite]): Future[immutable.Seq[Try[Unit]]]

  /**
   * Plugin API: asynchronously deletes all persistent messages up to `toSequenceNr`
   * (inclusive).
   */
  def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit]

  /**
   * Plugin API
   *
   * Allows plugin implementers to use `f pipeTo self` and
   * handle additional messages for implementing advanced features
   */
  def receivePluginInternal: Actor.Receive = Actor.emptyBehavior
  //#journal-plugin-api

}

/**
 * INTERNAL API.
 */
private[persistence] object AsyncWriteJournal {
  val successUnit: Success[Unit] = Success(())

  final case class Desequenced(msg: Any, snr: Long, target: ActorRef, sender: ActorRef)
    extends NoSerializationVerificationNeeded

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
        d.target.tell(d.msg, d.sender)
      } else {
        delayed += (d.snr -> d)
      }
      val ro = delayed.remove(delivered + 1)
      if (ro.isDefined) resequence(ro.get)
    }
  }
}

