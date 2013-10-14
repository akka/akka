/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.journal.japi;

import scala.concurrent.Future;

import akka.persistence.PersistentImpl;

interface AsyncWritePlugin {
    //#async-write-plugin-api
    /**
     * Plugin Java API.
     *
     * Asynchronously writes a `persistent` message to the journal.
     */
    Future<Void> doWriteAsync(PersistentImpl persistent);

    /**
     * Plugin Java API.
     *
     * Asynchronously marks a `persistent` message as deleted.
     */
    Future<Void> doDeleteAsync(PersistentImpl persistent);

    /**
     * Plugin Java API.
     *
     * Asynchronously writes a delivery confirmation to the journal.
     */
    Future<Void> doConfirmAsync(String processorId, long sequenceNr, String channelId);
    //#async-write-plugin-api
}
