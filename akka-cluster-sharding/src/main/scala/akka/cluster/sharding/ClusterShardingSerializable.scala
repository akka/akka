/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.sharding

/**
 * Marker trait for remote messages and persistent events/snapshots with special serializer.
 */
trait ClusterShardingSerializable extends Serializable
