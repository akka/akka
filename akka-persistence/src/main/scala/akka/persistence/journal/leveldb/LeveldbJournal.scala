/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
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
private[persistence] class LeveldbJournal extends { val configPath = "akka.persistence.journal.leveldb" } with SyncWriteJournal with LeveldbStore

/**
 * INTERNAL API.
 *
 * Journal backed by a [[SharedLeveldbStore]]. For testing only.
 */
private[persistence] class SharedLeveldbJournal extends AsyncWriteProxy {
  val timeout: Timeout = context.system.settings.config.getMillisDuration(
    "akka.persistence.journal.leveldb-shared.timeout")
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
