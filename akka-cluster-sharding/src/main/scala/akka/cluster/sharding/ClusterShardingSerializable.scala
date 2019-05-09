/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

/**
 * Marker trait for remote messages and persistent events/snapshots with special serializer.
 */
trait ClusterShardingSerializable extends Serializable
