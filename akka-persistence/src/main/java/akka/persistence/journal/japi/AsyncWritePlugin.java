/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.journal.japi;

import scala.concurrent.Future;

import akka.persistence.*;

interface AsyncWritePlugin {
    //#async-write-plugin-api
    /**
     * Java API, Plugin API: synchronously writes a batch of persistent messages to the
     * journal. The batch write must be atomic i.e. either all persistent messages in the
     * batch are written or none.
     */
    Future<Void> doAsyncWriteMessages(Iterable<PersistentRepr> messages);

    /**
     * Java API, Plugin API: synchronously writes a batch of delivery confirmations to
     * the journal.
     */
    Future<Void> doAsyncWriteConfirmations(Iterable<PersistentConfirmation> confirmations);

    /**
     * Java API, Plugin API: synchronously deletes messages identified by `messageIds`
     * from the journal. If `permanent` is set to `false`, the persistent messages are
     * marked as deleted, otherwise they are permanently deleted.
     */
    Future<Void> doAsyncDeleteMessages(Iterable<PersistentId> messageIds, boolean permanent);

    /**
     * Java API, Plugin API: synchronously deletes all persistent messages up to
     * `toSequenceNr`. If `permanent` is set to `false`, the persistent messages are
     * marked as deleted, otherwise they are permanently deleted.
     *
     * @see AsyncRecoveryPlugin
     */
    Future<Void> doAsyncDeleteMessagesTo(String processorId, long toSequenceNr, boolean permanent);
    //#async-write-plugin-api
}
