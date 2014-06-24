/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.journal.japi;

import scala.concurrent.Future;

import akka.japi.Procedure;
import akka.persistence.PersistentRepr;

interface AsyncRecoveryPlugin {
    //#async-replay-plugin-api
    /**
     * Java API, Plugin API: asynchronously replays persistent messages.
     * Implementations replay a message by calling `replayCallback`. The returned
     * future must be completed when all messages (matching the sequence number
     * bounds) have been replayed. The future must be completed with a failure if
     * any of the persistent messages could not be replayed.
     *
     * The `replayCallback` must also be called with messages that have been marked
     * as deleted. In this case a replayed message's `deleted` method must return
     * `true`.
     *
     * The channel ids of delivery confirmations that are available for a replayed
     * message must be contained in that message's `confirms` sequence.
     *
     * @param persistenceId processor id.
     * @param fromSequenceNr sequence number where replay should start (inclusive).
     * @param toSequenceNr sequence number where replay should end (inclusive).
     * @param max maximum number of messages to be replayed.
     * @param replayCallback called to replay a single message. Can be called from any
     *                       thread.
     */
    Future<Void> doAsyncReplayMessages(String persistenceId, long fromSequenceNr, long toSequenceNr, long max, Procedure<PersistentRepr> replayCallback);

    /**
     * Java API, Plugin API: asynchronously reads the highest stored sequence number
     * for the given `persistenceId`.
     *
     * @param persistenceId processor id.
     * @param fromSequenceNr hint where to start searching for the highest sequence
     *                       number.
     */
    Future<Long> doAsyncReadHighestSequenceNr(String persistenceId, long fromSequenceNr);
    //#async-replay-plugin-api
}
