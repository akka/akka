/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.journal.japi;

import akka.persistence.PersistentRepr;

interface SyncWritePlugin {
    //#sync-write-plugin-api
    /**
     * Java API, Plugin API: synchronously writes a `persistent` message to the journal.
     */
    void doWrite(PersistentRepr persistent) throws Exception;

    /**
     * Java API, Plugin API: synchronously writes a batch of persistent messages to the
     * journal. The batch write must be atomic i.e. either all persistent messages in the
     * batch are written or none.
     */
    void doWriteBatch(Iterable<PersistentRepr> persistentBatch);

    /**
     * Java API, Plugin API: synchronously deletes a persistent message. If `physical`
     * is set to `false`, the persistent message is marked as deleted, otherwise it is
     * physically deleted.
     */
    void doDelete(String processorId, long sequenceNr, boolean physical);

    /**
     * Java API, Plugin API: synchronously writes a delivery confirmation to the journal.
     */
    void doConfirm(String processorId, long sequenceNr, String channelId) throws Exception;
    //#sync-write-plugin-api
}
