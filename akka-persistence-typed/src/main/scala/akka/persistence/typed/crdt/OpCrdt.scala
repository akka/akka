/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.crdt

import akka.annotation.{ ApiMayChange, DoNotInherit }

@ApiMayChange
@DoNotInherit
trait OpCrdt[Operation] { self =>
  type T <: OpCrdt[Operation] { type T = self.T }

  def applyOperation(op: Operation): T
}
