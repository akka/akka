/*
 * Copyright (C) 2019-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.cluster.sharding.typed

import scala.annotation.nowarn
import akka.actor.ActorSystem
import akka.persistence.query.scaladsl.DurableStateStoreQuery
import akka.persistence.state.DurableStateStoreRegistry

@nowarn
object DurableStateStoreQueryUsageCompileOnlySpec {
  def getQuery[Record](system: ActorSystem, pluginId: String) = {
    //#get-durable-state-store-query-example
    val durableStateStoreQuery =
      DurableStateStoreRegistry(system)
        .durableStateStoreFor[DurableStateStoreQuery[Record]](pluginId)
    //#get-durable-state-store-query-example
  }
}
