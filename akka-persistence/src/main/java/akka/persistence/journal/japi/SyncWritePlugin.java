/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.journal.japi;

import akka.persistence.*;

interface SyncWritePlugin {
  //#sync-write-plugin-api
  /**
   * Java API, Plugin API: synchronously writes a batch of persistent messages
   * to the journal. The batch write must be atomic i.e. either all persistent
   * messages in the batch are written or none.
   */
  void doWriteMessages(Iterable<PersistentRepr> messages);

  /**
   * Java API, Plugin API: synchronously deletes all persistent messages up to
   * `toSequenceNr`. If `permanent` is set to `false`, the persistent messages
   * are marked as deleted, otherwise they are permanently deleted.
   *
   * @see AsyncRecoveryPlugin
   */
  void doDeleteMessagesTo(String persistenceId, long toSequenceNr, boolean permanent);
  //#sync-write-plugin-api
}
