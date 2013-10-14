/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.journal.japi

import java.lang.{ Long ⇒ JLong }

import scala.concurrent.Future

import akka.actor.Actor
import akka.japi.Procedure
import akka.persistence.journal.{ AsyncReplay ⇒ SAsyncReplay }
import akka.persistence.PersistentImpl

/**
 * Java API.
 *
 * Asynchronous message replay interface.
 */
abstract class AsyncReplay extends SAsyncReplay with AsyncReplayPlugin { this: Actor ⇒
  import context.dispatcher

  final def replayAsync(processorId: String, fromSequenceNr: Long, toSequenceNr: Long)(replayCallback: (PersistentImpl) ⇒ Unit) =
    doReplayAsync(processorId, fromSequenceNr, toSequenceNr, new Procedure[PersistentImpl] {
      def apply(p: PersistentImpl) = replayCallback(p)
    }).map(_.longValue)
}
