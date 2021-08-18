/*
 * Copyright (C) 2019-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.cluster.sharding.typed

import scala.annotation.nowarn

import akka.NotUsed
import akka.actor.ActorSystem
import akka.persistence.query.Offset
import akka.stream.scaladsl.Source

@nowarn
object DurableStateStoreQueryUsageCompileOnlySpec {
  def getQuery[Record](system: ActorSystem, pluginId: String, offset: Offset) = {
    //#get-durable-state-store-query-example
    import akka.persistence.state.DurableStateStoreRegistry
    import akka.persistence.query.scaladsl.DurableStateStoreQuery
    import akka.persistence.query.DurableStateChange
    import akka.persistence.query.UpdatedDurableState

    val durableStateStoreQuery =
      DurableStateStoreRegistry(system).durableStateStoreFor[DurableStateStoreQuery[Record]](pluginId)
    val source: Source[DurableStateChange, NotUsed] = durableStateStoreQuery.changes("tag", offset)
    source.map {
      case UpdatedDurableState(persistenceId, revision, value, offset, timestamp) => value
    }
    //#get-durable-state-store-query-example
  }
}
