/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.journal.japi;

import akka.persistence.PersistentRepr;

interface SyncWritePlugin {
    //#sync-write-plugin-api
    /**
     * Java API, Plugin API: synchronously writes a batch of persistent messages to the
     * journal. The batch write must be atomic i.e. either all persistent messages in the
     * batch are written or none.
     */
    void doWrite(Iterable<PersistentRepr> persistentBatch);

    /**
     * Java API, Plugin API: synchronously deletes all persistent messages within the
     * range  from `fromSequenceNr` to `toSequenceNr`. If `permanent` is set to `false`,
     * the persistent messages are marked as deleted, otherwise they are permanently
     * deleted.
     *
     * @see AsyncReplayPlugin
     */
    void doDelete(String processorId, long fromSequenceNr, long toSequenceNr, boolean permanent);

    /**
     * Java API, Plugin API: synchronously writes a delivery confirmation to the journal.
     */
    void doConfirm(String processorId, long sequenceNr, String channelId) throws Exception;
    //#sync-write-plugin-api
}
