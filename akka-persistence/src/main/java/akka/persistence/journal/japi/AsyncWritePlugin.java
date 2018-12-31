/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.journal.japi;

import java.util.Optional;

import scala.concurrent.Future;

import akka.persistence.*;

interface AsyncWritePlugin {
  //#async-write-plugin-api
  /**
   * Java API, Plugin API: asynchronously writes a batch (`Iterable`) of
   * persistent messages to the journal.
   *
   * The batch is only for performance reasons, i.e. all messages don't have to
   * be written atomically. Higher throughput can typically be achieved by using
   * batch inserts of many records compared to inserting records one-by-one, but
   * this aspect depends on the underlying data store and a journal
   * implementation can implement it as efficient as possible. Journals should
   * aim to persist events in-order for a given `persistenceId` as otherwise in
   * case of a failure, the persistent state may be end up being inconsistent.
   * 
   * Each `AtomicWrite` message contains the single `PersistentRepr` that
   * corresponds to the event that was passed to the `persist` method of the
   * `PersistentActor`, or it contains several `PersistentRepr` that corresponds
   * to the events that were passed to the `persistAll` method of the
   * `PersistentActor`. All `PersistentRepr` of the `AtomicWrite` must be
   * written to the data store atomically, i.e. all or none must be stored. If
   * the journal (data store) cannot support atomic writes of multiple events it
   * should reject such writes with an `Optional` with an
   * `UnsupportedOperationException` describing the issue. This limitation
   * should also be documented by the journal plugin.
   *
   * If there are failures when storing any of the messages in the batch the
   * returned `Future` must be completed with failure. The `Future` must only be
   * completed with success when all messages in the batch have been confirmed
   * to be stored successfully, i.e. they will be readable, and visible, in a
   * subsequent replay. If there is uncertainty about if the messages were
   * stored or not the `Future` must be completed with failure.
   *
   * Data store connection problems must be signaled by completing the `Future`
   * with failure.
   *
   * The journal can also signal that it rejects individual messages
   * (`AtomicWrite`) by the returned
   * `Iterable&lt;Optional&lt;Exception&gt;&gt;`. The returned `Iterable` must
   * have as many elements as the input `messages` `Iterable`. Each `Optional`
   * element signals if the corresponding `AtomicWrite` is rejected or not, with
   * an exception describing the problem. Rejecting a message means it was not
   * stored, i.e. it must not be included in a later replay. Rejecting a message
   * is typically done before attempting to store it, e.g. because of
   * serialization error.
   *
   * Data store connection problems must not be signaled as rejections.
   *
   * Note that it is possible to reduce number of allocations by caching some
   * result `Iterable` for the happy path, i.e. when no messages are rejected.
   *
   * Calls to this method are serialized by the enclosing journal actor. If you spawn
   * work in asynchronous tasks it is alright that they complete the futures in any order,
   * but the actual writes for a specific persistenceId should be serialized to avoid 
   * issues such as events of a later write are visible to consumers (query side, or replay)
   * before the events of an earlier write are visible. This can also be done with
   * consistent hashing if it is too fine grained to do it on the persistenceId level.
   * Normally a `PersistentActor` will only have one outstanding write request to the journal but 
   * it may emit several write requests when `persistAsync` is used and the max batch size
   * is reached.
   *
   * This call is protected with a circuit-breaker.
   */
  Future<Iterable<Optional<Exception>>> doAsyncWriteMessages(Iterable<AtomicWrite> messages);

  /**
   * Java API, Plugin API: synchronously deletes all persistent messages up to
   * `toSequenceNr`.
   *
   * This call is protected with a circuit-breaker.
   *
   * @see AsyncRecoveryPlugin
   */
  Future<Void> doAsyncDeleteMessagesTo(String persistenceId, long toSequenceNr);
  //#async-write-plugin-api
}
