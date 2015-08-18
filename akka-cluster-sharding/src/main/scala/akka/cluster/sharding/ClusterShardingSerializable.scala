/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster.sharding

/**
 * Marker trait for remote messages and persistent events/snapshots with special serializer.
 */
trait ClusterShardingSerializable extends Serializable
