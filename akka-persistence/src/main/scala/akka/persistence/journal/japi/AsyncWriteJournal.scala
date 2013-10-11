/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.journal.japi

import scala.concurrent.Future

import akka.persistence.journal.{ AsyncWriteJournal ⇒ SAsyncWriteJournal }
import akka.persistence.PersistentImpl

/**
 * Java API.
 *
 * Abstract journal, optimized for asynchronous, non-blocking writes.
 */
abstract class AsyncWriteJournal extends AsyncReplay with SAsyncWriteJournal {
  import context.dispatcher

  final def writeAsync(persistent: PersistentImpl) =
    doWriteAsync(persistent).map(Unit.unbox)

  final def deleteAsync(persistent: PersistentImpl) =
    doDeleteAsync(persistent).map(Unit.unbox)

  final def confirmAsync(processorId: String, sequenceNr: Long, channelId: String) =
    doConfirmAsync(processorId, sequenceNr, channelId).map(Unit.unbox)

  /**
   * Plugin Java API.
   *
   * Asynchronously writes a `persistent` message to the journal.
   */
  def doWriteAsync(persistent: PersistentImpl): Future[Void]

  /**
   * Plugin Java API.
   *
   * Asynchronously marks a `persistent` message as deleted.
   */
  def doDeleteAsync(persistent: PersistentImpl): Future[Void]

  /**
   * Plugin Java API.
   *
   * Asynchronously writes a delivery confirmation to the journal.
   */
  def doConfirmAsync(processorId: String, sequenceNr: Long, channelId: String): Future[Void]
}
