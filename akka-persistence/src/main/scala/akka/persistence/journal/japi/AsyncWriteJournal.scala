/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.journal.japi

import scala.collection.immutable
import scala.collection.JavaConverters._

import akka.persistence._
import akka.persistence.journal.{ AsyncWriteJournal ⇒ SAsyncWriteJournal }

/**
 * Java API: abstract journal, optimized for asynchronous, non-blocking writes.
 */
abstract class AsyncWriteJournal extends AsyncRecovery with SAsyncWriteJournal with AsyncWritePlugin {
  import context.dispatcher

  final def asyncWriteMessages(messages: immutable.Seq[PersistentRepr]) =
    doAsyncWriteMessages(messages.asJava).map(Unit.unbox)

  final def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long, permanent: Boolean) =
    doAsyncDeleteMessagesTo(persistenceId, toSequenceNr, permanent).map(Unit.unbox)
}
