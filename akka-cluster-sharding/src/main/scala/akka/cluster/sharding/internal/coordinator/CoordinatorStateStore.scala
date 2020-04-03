/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.cluster.sharding.internal.coordinator

import akka.annotation.InternalApi
import akka.cluster.sharding.ShardCoordinator.Internal.DomainEvent
import akka.cluster.sharding.ShardCoordinator.Internal.State

/**
 * INTERNAL API
 *
 * Protocol for coordinator state store actors
 */
@InternalApi
private[akka] object CoordinatorStateStore {
  sealed trait Command

  case object GetInitialState extends Command
  final case class InitialState(state: State)

  // FIXME should we hide the domain events in the persistent one and create a command for each operation instead?
  // Nope because the state is the thing stored in the DData one
  // while the domain events are needed in the persistent one
  // what about set of commands, then the ack contains the new state and the coordinator newer modifies?
  // - that doesn't work with the initial combination of remember entities and state load
  final case class UpdateState(event: DomainEvent) extends Command
  final case class StateUpdateDone(event: DomainEvent)

}
