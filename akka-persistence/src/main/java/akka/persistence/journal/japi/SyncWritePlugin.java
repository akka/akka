/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.journal.japi;

import akka.persistence.PersistentImpl;

interface SyncWritePlugin {
    //#sync-write-plugin-api
    /**
     * Plugin Java API.
     *
     * Synchronously writes a `persistent` message to the journal.
     */
    void doWrite(PersistentImpl persistent) throws Exception;

    /**
     * Plugin Java API.
     *
     * Synchronously writes a batch of persistent messages to the journal. The batch write
     * must be atomic i.e. either all persistent messages in the batch are written or none.
     */
    void doWriteBatch(Iterable<PersistentImpl> persistentBatch);

    /**
     * Plugin Java API.
     *
     * Synchronously marks a `persistent` message as deleted.
     */
    void doDelete(PersistentImpl persistent) throws Exception;

    /**
     * Plugin Java API.
     *
     * Synchronously writes a delivery confirmation to the journal.
     */
    void doConfirm(String processorId, long sequenceNr, String channelId) throws Exception;
    //#sync-write-plugin-api
}
