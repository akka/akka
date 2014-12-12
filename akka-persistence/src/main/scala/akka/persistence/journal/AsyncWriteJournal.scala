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
    case WriteMessages(messages, persistentActor, actorInstanceId) ⇒
      val cctr = resequencerCounter
      def resequence(f: PersistentRepr ⇒ Any) = messages.zipWithIndex.foreach {
        case (p: PersistentRepr, i) ⇒ resequencer ! Desequenced(f(p), cctr + i + 1, persistentActor, p.sender)
        case (r, i)                 ⇒ resequencer ! Desequenced(LoopMessageSuccess(r.payload, actorInstanceId), cctr + i + 1, persistentActor, r.sender)
      }
      asyncWriteMessages(preparePersistentBatch(messages)) onComplete {
        case Success(_) ⇒
          resequencer ! Desequenced(WriteMessagesSuccessful, cctr, persistentActor, self)
          resequence(WriteMessageSuccess(_, actorInstanceId))
        case Failure(e) ⇒
          resequencer ! Desequenced(WriteMessagesFailed(e), cctr, persistentActor, self)
          resequence(WriteMessageFailure(_, e, actorInstanceId))
      }
      resequencerCounter += messages.length + 1
    case r @ ReplayMessages(fromSequenceNr, toSequenceNr, max, persistenceId, persistentActor, replayDeleted) ⇒
      // Send replayed messages and replay result to persistentActor directly. No need
      // to resequence replayed messages relative to written and looped messages.
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
      // Send read highest sequence number to persistentActor directly. No need
      // to resequence the result relative to written and looped messages.
      asyncReadHighestSequenceNr(persistenceId, fromSequenceNr).map {
        highest ⇒ ReadHighestSequenceNrSuccess(highest)
      } recover {
        case e ⇒ ReadHighestSequenceNrFailure(e)
      } pipeTo (persistentActor)
    case d @ DeleteMessagesTo(persistenceId, toSequenceNr, permanent) ⇒
      asyncDeleteMessagesTo(persistenceId, toSequenceNr, permanent) onComplete {
        case Success(_) ⇒ if (publish) context.system.eventStream.publish(d)
        case Failure(e) ⇒
      }
  }

  //#journal-plugin-api
  /**
   * Plugin API: asynchronously writes a batch of persistent messages to the journal.
   * The batch write must be atomic i.e. either all persistent messages in the batch
   * are written or none.
   */
  def asyncWriteMessages(messages: immutable.Seq[PersistentRepr]): Future[Unit]

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
        d.target.tell(d.msg, d.sender)
      } else {
        delayed += (d.snr -> d)
      }
      val ro = delayed.remove(delivered + 1)
      if (ro.isDefined) resequence(ro.get)
    }
  }
}

