/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed

import java.util.Optional

import akka.annotation.DoNotInherit
import akka.persistence.typed.internal.ReplicatedPublishedEventMetaData

/**
 * When using event publishing the events published to the system event stream will be in this form.
 *
 * Not for user extension
 */
@DoNotInherit
trait PublishedEvent {

  /** Scala API: When emitted from an Replicated Event Sourcing actor this will contain the replica id */
  def replicatedMetaData: Option[ReplicatedPublishedEventMetaData]

  /** Java API: When emitted from an Replicated Event Sourcing actor this will contain the replica id */
  def getReplicatedMetaData: Optional[ReplicatedPublishedEventMetaData]

  def persistenceId: PersistenceId
  def sequenceNumber: Long

  /** User event */
  def event: Any
  def timestamp: Long
  def tags: Set[String]
}
