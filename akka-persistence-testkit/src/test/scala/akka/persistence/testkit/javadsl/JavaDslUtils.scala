/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.javadsl

import akka.persistence.typed.javadsl.{ CommandHandler, EventHandler, EventSourcedBehavior }
import akka.persistence.testkit.{ Cmd, CommonUtils, EmptyState, Evt }
import akka.persistence.typed.PersistenceId

trait JavaDslUtils extends CommonUtils {

  def eventSourcedBehavior(pid: String) =
    new EventSourcedBehavior[Cmd, Evt, EmptyState](PersistenceId.ofUniqueId(pid)) {

      override protected def emptyState: EmptyState = EmptyState()

      override protected def commandHandler(): CommandHandler[Cmd, Evt, EmptyState] =
        newCommandHandlerBuilder().forAnyState().onAnyCommand((command: Cmd) => Effect.persist(Evt(command.data)))

      override protected def eventHandler(): EventHandler[EmptyState, Evt] =
        newEventHandlerBuilder().forAnyState().onAnyEvent(_ => emptyState)

      override def shouldSnapshot(state: EmptyState, event: Evt, sequenceNr: Long): Boolean = true
    }

}
