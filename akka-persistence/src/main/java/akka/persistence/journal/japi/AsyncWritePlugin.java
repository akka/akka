/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.journal.japi;

import scala.concurrent.Future;

import akka.persistence.PersistentRepr;

interface AsyncWritePlugin {
    //#async-write-plugin-api
    /**
     * Java API, Plugin API: asynchronously writes a `persistent` message to the journal.
     */
    Future<Void> doWriteAsync(PersistentRepr persistent);

    /**
     * Java API, Plugin API: asynchronously writes a batch of persistent messages to the
     * journal. The batch write must be atomic i.e. either all persistent messages in the
     * batch are written or none.
     */
    Future<Void> doWriteBatchAsync(Iterable<PersistentRepr> persistentBatch);

    /**
     * Java API, Plugin API: asynchronously deletes a persistent message. If `physical`
     * is set to `false`, the persistent message is marked as deleted, otherwise it is
     * physically deleted.
     */
    Future<Void> doDeleteAsync(String processorId, long sequenceNr, boolean physical);

    /**
     * Java API, Plugin API: asynchronously writes a delivery confirmation to the
     * journal.
     */
    Future<Void> doConfirmAsync(String processorId, long sequenceNr, String channelId);
    //#async-write-plugin-api
}
