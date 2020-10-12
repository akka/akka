/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.testkit.scaladsl

import akka.actor.typed.ActorRef
import akka.cluster.sharding.typed.internal.testkit.TestEntityRefImpl
import akka.cluster.sharding.typed.scaladsl.EntityRef
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.util.unused

/**
 * For testing purposes this `EntityRef` can be used in place of a real [[EntityRef]].
 * It forwards all messages to the `probe`.
 */
object TestEntityRef {
  def apply[M](@unused typeKey: EntityTypeKey[M], entityId: String, probe: ActorRef[M]): EntityRef[M] =
    new TestEntityRefImpl[M](entityId, probe)
}
