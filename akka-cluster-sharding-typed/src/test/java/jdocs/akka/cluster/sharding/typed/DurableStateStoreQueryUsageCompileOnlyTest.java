/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.cluster.sharding.typed;

import akka.actor.ActorSystem;
import akka.persistence.query.javadsl.DurableStateStoreQuery;
import akka.persistence.state.DurableStateStoreRegistry;
import akka.persistence.state.javadsl.*;

class DurableStateStoreQueryUsageCompileOnlySpec {

  public <Record> DurableStateStoreQuery<Record> getQuery(ActorSystem system, String pluginId) {
    DurableStateStoreQuery<?> durableStateStoreQuery1 = null;
    // #get-durable-state-store-query-example
    DurableStateStoreQuery<Record> durableStateStoreQuery =
        DurableStateStoreRegistry.get(system)
            .getDurableStateStoreFor(DurableStateStoreQuery.class, pluginId);
    // #get-durable-state-store-query-example
    return durableStateStoreQuery;
  }
}
