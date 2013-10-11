/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.journal.japi

import akka.persistence.journal.{ SyncWriteJournal ⇒ SSyncWriteJournal }
import akka.persistence.PersistentImpl

/**
 * Java API.
 *
 * Abstract journal, optimized for synchronous writes.
 */
abstract class SyncWriteJournal extends AsyncReplay with SSyncWriteJournal {
  final def write(persistent: PersistentImpl) =
    doWrite(persistent)

  final def delete(persistent: PersistentImpl) =
    doDelete(persistent)

  final def confirm(processorId: String, sequenceNr: Long, channelId: String) =
    doConfirm(processorId, sequenceNr, channelId)

  /**
   * Plugin Java API.
   *
   * Synchronously writes a `persistent` message to the journal.
   */
  @throws(classOf[Exception])
  def doWrite(persistent: PersistentImpl): Unit

  /**
   * Plugin Java API.
   *
   * Synchronously marks a `persistent` message as deleted.
   */
  @throws(classOf[Exception])
  def doDelete(persistent: PersistentImpl): Unit

  /**
   * Plugin Java API.
   *
   * Synchronously writes a delivery confirmation to the journal.
   */
  @throws(classOf[Exception])
  def doConfirm(processorId: String, sequenceNr: Long, channelId: String): Unit
}
