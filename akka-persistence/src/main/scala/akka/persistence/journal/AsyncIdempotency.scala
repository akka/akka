/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.journal

import scala.concurrent.Future

trait AsyncIdempotency {

  def asyncReadHighestIdempotencyKeySequenceNr(persistenceId: String): Future[Long]

  def asyncReadIdempotencyKeys(persistenceId: String, toSequenceNr: Long, max: Long)(
      readCallback: (String, Long) => Unit): Future[Unit]

  def asyncCheckIdempotencyKeyExists(persistenceId: String, key: String): Future[Boolean]

  def asyncWriteIdempotencyKey(
      persistenceId: String,
      key: String,
      sequenceNr: Long,
      highestEventSequenceNr: Long): Future[Unit]
}
