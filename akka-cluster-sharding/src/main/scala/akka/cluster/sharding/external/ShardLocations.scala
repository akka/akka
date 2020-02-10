/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.external

import akka.cluster.sharding.ShardRegion.ShardId
import akka.cluster.sharding.external.ExternalShardAllocationStrategy.ShardLocation
import akka.util.ccompat.JavaConverters._

final class ShardLocations(val locations: Map[ShardId, ShardLocation]) {

  /**
   * Java API
   */
  def getShardLocations(): java.util.Map[ShardId, ShardLocation] = locations.asJava
}
