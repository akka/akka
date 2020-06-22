/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed

import akka.annotation.InternalApi
import akka.persistence.journal.Tagged

/**
 * INTERNAL API
 */
// FIXME internal for now but perhaps useful as public as well?
@InternalApi
private[akka] final case class PublishedEvent(
    persistenceId: PersistenceId,
    sequenceNumber: Long,
    payload: Any,
    timestamp: Long) {

  def tags: Set[String] = payload match {
    case t: Tagged => t.tags
    case _         => Set.empty
  }

  def event: Any = payload match {
    case Tagged(event, _) => event
    case _                => payload
  }

}
