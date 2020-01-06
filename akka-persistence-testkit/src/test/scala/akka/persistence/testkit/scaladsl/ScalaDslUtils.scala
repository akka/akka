/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.scaladsl

import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior }
import akka.persistence.testkit.{ Cmd, CommonUtils, EmptyState, Evt }
import akka.persistence.typed.PersistenceId

trait ScalaDslUtils extends CommonUtils {

  def eventSourcedBehavior(pid: String) =
    EventSourcedBehavior[Cmd, Evt, EmptyState](
      PersistenceId.ofUniqueId(pid),
      EmptyState(),
      (_, cmd) => Effect.persist(Evt(cmd.data)),
      (_, _) => EmptyState()).snapshotWhen((_, _, _) => true)

}
