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
    case Terminated(ref) ⇒
      removeSubscriber(ref)
  }
}

/**
 * INTERNAL API.
 */
private[persistence] object LeveldbJournal {
  /**
   * Subscribe the `sender` to changes (append events) for a specific `persistenceId`.
   * Used by query-side. The journal will send [[ChangedPersistenceId]] messages to
   * the subscriber when `asyncWriteMessages` has been called.
   */
  case class SubscribePersistenceId(persistenceId: String)
  case class ChangedPersistenceId(persistenceId: String) extends DeadLetterSuppression
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
    case m: LeveldbJournal.SubscribePersistenceId ⇒
      // forward subscriptions, they are used by query-side
      store match {
        case Some(s) ⇒ s.forward(m)
        case None ⇒
          log.error("Failed SubscribePersistenceId({}) request. " +
            "Store not initialized. Use `SharedLeveldbJournal.setStore(sharedStore, system)`", m.persistenceId)
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
