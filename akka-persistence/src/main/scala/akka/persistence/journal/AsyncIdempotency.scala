/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.journal

import scala.concurrent.Future

trait AsyncIdempotency {

  def asyncCheckIdempotencyKeyExists(persistenceId: String, key: String): Future[Boolean]

  def asyncWriteIdempotencyKey(persistenceId: String, key: String): Future[Unit]
}
