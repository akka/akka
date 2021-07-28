/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.cluster.sharding.typed;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.stream.javadsl.Source;
import akka.persistence.query.DurableStateChange;
import akka.persistence.query.Offset;
import akka.persistence.query.javadsl.DurableStateStoreQuery;
import akka.persistence.state.DurableStateStoreRegistry;
import akka.persistence.state.javadsl.*;

class DurableStateStoreQueryUsageCompileOnlySpec {

  public <Record> DurableStateStoreQuery<Record> getQuery(
      ActorSystem system, String pluginId, Offset offset) {
    // #get-durable-state-store-query-example
    DurableStateStoreQuery<Record> durableStateStoreQuery =
        DurableStateStoreRegistry.get(system)
            .getDurableStateStoreFor(DurableStateStoreQuery.class, pluginId);
    Source<DurableStateChange<Record>, NotUsed> source =
        durableStateStoreQuery.changes("tag", offset);
    // #get-durable-state-store-query-example
    return durableStateStoreQuery;
  }
}
