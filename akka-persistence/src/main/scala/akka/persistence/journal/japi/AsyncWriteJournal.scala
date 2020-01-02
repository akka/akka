/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.journal.japi

import scala.collection.immutable
import akka.util.ccompat.JavaConverters._
import akka.persistence._
import akka.persistence.journal.{ AsyncWriteJournal => SAsyncWriteJournal }
import akka.util.ccompat._
import scala.concurrent.Future
import scala.util.Try
import scala.util.Failure

/**
 * Java API: abstract journal, optimized for asynchronous, non-blocking writes.
 */
@ccompatUsedUntil213
abstract class AsyncWriteJournal extends AsyncRecovery with SAsyncWriteJournal with AsyncWritePlugin {
  import SAsyncWriteJournal.successUnit
  import context.dispatcher

  final def asyncWriteMessages(messages: immutable.Seq[AtomicWrite]): Future[immutable.Seq[Try[Unit]]] =
    doAsyncWriteMessages(messages.asJava).map { results =>
      results.asScala.iterator
        .map { r =>
          if (r.isPresent) Failure(r.get)
          else successUnit
        }
        .to(immutable.IndexedSeq)
    }

  final def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long) =
    doAsyncDeleteMessagesTo(persistenceId, toSequenceNr).map(_ => ())
}
