/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.persistence.journal.leveldb

import akka.actor._
import akka.persistence.Persistence
import akka.persistence.journal._
import akka.util.Timeout
import akka.util.Helpers.ConfigOps
import akka.persistence.PersistentRepr
import scala.concurrent.Future
import akka.persistence.JournalProtocol.RecoverySuccess
import akka.persistence.JournalProtocol.ReplayMessagesFailure
import akka.pattern.pipe

/**
 * INTERNAL API.
 *
 * Journal backed by a local LevelDB store. For production use.
 */
private[persistence] class LeveldbJournal extends { val configPath = "akka.persistence.journal.leveldb" } with AsyncWriteJournal with LeveldbStore {
  import LeveldbJournal._

  override def receivePluginInternal: Receive = {
    case r @ ReplayTaggedMessages(fromSequenceNr, toSequenceNr, max, tag, replyTo) ⇒
      import context.dispatcher
      val readHighestSequenceNrFrom = math.max(0L, fromSequenceNr - 1)
      asyncReadHighestSequenceNr(tagAsPersistenceId(tag), readHighestSequenceNrFrom)
        .flatMap { highSeqNr ⇒
          val toSeqNr = math.min(toSequenceNr, highSeqNr)
          if (highSeqNr == 0L || fromSequenceNr > toSeqNr)
            Future.successful(highSeqNr)
          else {
            asyncReplayTaggedMessages(tag, fromSequenceNr, toSeqNr, max) {
              case ReplayedTaggedMessage(p, tag, offset) ⇒
                adaptFromJournal(p).foreach { adaptedPersistentRepr ⇒
                  replyTo.tell(ReplayedTaggedMessage(adaptedPersistentRepr, tag, offset), Actor.noSender)
                }
            }.map(_ ⇒ highSeqNr)
          }
        }.map {
          highSeqNr ⇒ RecoverySuccess(highSeqNr)
        }.recover {
          case e ⇒ ReplayMessagesFailure(e)
        }.pipeTo(replyTo)

    case SubscribePersistenceId(persistenceId: String) ⇒
      addPersistenceIdSubscriber(sender(), persistenceId)
      context.watch(sender())
    case SubscribeAllPersistenceIds ⇒
      addAllPersistenceIdsSubscriber(sender())
      context.watch(sender())
    case SubscribeTag(tag: String) ⇒
      addTagSubscriber(sender(), tag)
      context.watch(sender())
    case Terminated(ref) ⇒
      removeSubscriber(ref)
  }
}

/**
 * INTERNAL API.
 */
private[persistence] object LeveldbJournal {
  sealed trait SubscriptionCommand

  /**
   * Subscribe the `sender` to changes (appended events) for a specific `persistenceId`.
   * Used by query-side. The journal will send [[EventAppended]] messages to
   * the subscriber when `asyncWriteMessages` has been called.
   */
  final case class SubscribePersistenceId(persistenceId: String) extends SubscriptionCommand
  final case class EventAppended(persistenceId: String) extends DeadLetterSuppression

  /**
   * Subscribe the `sender` to current and new persistenceIds.
   * Used by query-side. The journal will send one [[CurrentPersistenceIds]] to the
   * subscriber followed by [[PersistenceIdAdded]] messages when new persistenceIds
   * are created.
   */
  final case object SubscribeAllPersistenceIds extends SubscriptionCommand
  final case class CurrentPersistenceIds(allPersistenceIds: Set[String]) extends DeadLetterSuppression
  final case class PersistenceIdAdded(persistenceId: String) extends DeadLetterSuppression

  /**
   * Subscribe the `sender` to changes (appended events) for a specific `tag`.
   * Used by query-side. The journal will send [[TaggedEventAppended]] messages to
   * the subscriber when `asyncWriteMessages` has been called.
   * Events are tagged by wrapping in [[akka.persistence.journal.Tagged]]
   * via an [[akka.persistence.journal.EventAdapter]].
   */
  final case class SubscribeTag(tag: String) extends SubscriptionCommand
  final case class TaggedEventAppended(tag: String) extends DeadLetterSuppression

  final case class ReplayTaggedMessages(fromSequenceNr: Long, toSequenceNr: Long, max: Long,
    tag: String, replyTo: ActorRef) extends SubscriptionCommand
  final case class ReplayedTaggedMessage(persistent: PersistentRepr, tag: String, offset: Long)
    extends DeadLetterSuppression with NoSerializationVerificationNeeded
}

/**
 * INTERNAL API.
 *
 * Journal backed by a [[SharedLeveldbStore]]. For testing only.
 */
private[persistence] class SharedLeveldbJournal extends AsyncWriteProxy {
  val timeout: Timeout = context.system.settings.config.getMillisDuration(
    "akka.persistence.journal.leveldb-shared.timeout")

  override def receivePluginInternal: Receive = {
    case cmd: LeveldbJournal.SubscriptionCommand ⇒
      // forward subscriptions, they are used by query-side
      store match {
        case Some(s) ⇒ s.forward(cmd)
        case None ⇒
          log.error("Failed {} request. " +
            "Store not initialized. Use `SharedLeveldbJournal.setStore(sharedStore, system)`", cmd)
      }

  }
}

object SharedLeveldbJournal {
  /**
   * Sets the shared LevelDB `store` for the given actor `system`.
   *
   * @see [[SharedLeveldbStore]]
   */
  def setStore(store: ActorRef, system: ActorSystem): Unit =
    Persistence(system).journalFor(null) ! AsyncWriteProxy.SetStore(store)
}
