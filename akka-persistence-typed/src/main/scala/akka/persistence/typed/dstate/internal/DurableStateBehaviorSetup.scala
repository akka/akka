/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.dstate.internal

import akka.actor.typed.Signal
import akka.actor.typed.scaladsl.ActorContext
import akka.annotation.InternalApi
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.dstate.scaladsl.DurableStateBehavior
import akka.persistence.typed.internal.InternalProtocol

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class DurableStateBehaviorSetup[C, S](
    val context: ActorContext[InternalProtocol],
    val persistenceId: PersistenceId,
    val emptyState: S,
    val commandHandler: DurableStateBehavior.CommandHandler[C, S],
    private val signalHandler: PartialFunction[(S, Signal), Unit]
) {}
