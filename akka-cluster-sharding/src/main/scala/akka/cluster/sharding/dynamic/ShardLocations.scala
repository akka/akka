/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.dynamic

import akka.actor.NoSerializationVerificationNeeded
import akka.cluster.sharding.ShardRegion.ShardId
import akka.cluster.sharding.dynamic.ExternalShardAllocationStrategy.ShardLocation
import akka.util.ccompat.JavaConverters._

// only returned locally from a DynamicShardAllocationClient
final class ShardLocations(val locations: Map[ShardId, ShardLocation]) extends NoSerializationVerificationNeeded {

  /**
   * Java API
   */
  def getShardLocations(): java.util.Map[ShardId, ShardLocation] = locations.asJava
}
