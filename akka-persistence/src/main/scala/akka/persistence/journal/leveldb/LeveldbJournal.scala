/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.persistence.journal.leveldb

import scala.concurrent.duration._
import scala.language.postfixOps

import akka.actor._
import akka.persistence.Persistence
import akka.persistence.journal._
import akka.util.Timeout
import akka.util.Helpers.ConfigOps

/**
 * INTERNAL API.
 *
 * Journal backed by a local LevelDB store. For production use.
 */
private[persistence] class LeveldbJournal extends { val configPath = "akka.persistence.journal.leveldb" } with AsyncWriteJournal with LeveldbStore {
  import LeveldbJournal._

  override def receivePluginInternal: Receive = {
    case SubscribePersistenceId(persistenceId: String) ⇒
      addPersistenceIdSubscriber(sender(), persistenceId)
      context.watch(sender())
    case SubscribeAllPersistenceIds ⇒
      addAllPersistenceIdsSubscriber(sender())
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
  case class SubscribePersistenceId(persistenceId: String) extends SubscriptionCommand
  case class EventAppended(persistenceId: String) extends DeadLetterSuppression

  /**
   * Subscribe the `sender` to changes (appended events) for a specific `persistenceId`.
   * Used by query-side. The journal will send one [[CurrentPersistenceIds]] to the
   * subscriber followed by [[PersistenceIdAdded]] messages when new persistenceIds
   * are created.
   */
  case object SubscribeAllPersistenceIds extends SubscriptionCommand
  case class CurrentPersistenceIds(allPersistenceIds: Set[String]) extends DeadLetterSuppression
  case class PersistenceIdAdded(persistenceId: String) extends DeadLetterSuppression
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
