/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.journal.japi

import scala.collection.immutable
import scala.collection.JavaConverters._

import akka.persistence.journal.{ AsyncWriteJournal ⇒ SAsyncWriteJournal }
import akka.persistence.PersistentRepr

/**
 * Java API: abstract journal, optimized for asynchronous, non-blocking writes.
 */
abstract class AsyncWriteJournal extends AsyncReplay with SAsyncWriteJournal with AsyncWritePlugin {
  import context.dispatcher

  final def writeAsync(persistent: PersistentRepr) =
    doWriteAsync(persistent).map(Unit.unbox)

  final def writeBatchAsync(persistentBatch: immutable.Seq[PersistentRepr]) =
    doWriteBatchAsync(persistentBatch.asJava).map(Unit.unbox)

  final def deleteAsync(processorId: String, sequenceNr: Long, physical: Boolean) =
    doDeleteAsync(processorId, sequenceNr, physical).map(Unit.unbox)

  final def confirmAsync(processorId: String, sequenceNr: Long, channelId: String) =
    doConfirmAsync(processorId, sequenceNr, channelId).map(Unit.unbox)
}
