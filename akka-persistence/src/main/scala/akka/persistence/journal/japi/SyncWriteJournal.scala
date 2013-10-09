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
abstract class SyncWriteJournal extends AsyncReplay with SSyncWriteJournal with SyncWritePlugin {
  final def write(persistent: PersistentImpl) =
    doWrite(persistent)

  final def delete(persistent: PersistentImpl) =
    doDelete(persistent)

  final def confirm(processorId: String, sequenceNr: Long, channelId: String) =
    doConfirm(processorId, sequenceNr, channelId)
}
