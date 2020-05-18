/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.internal.jfr

import akka.cluster.sharding.ShardingFlightRecorder
class JFRShardingFlightRecorder extends ShardingFlightRecorder {
  override def shardingBlockedOnRememberedEntities(duration: Long): Unit =
    new BlockedOnRememberUpdate(duration).commit()
  override def shardingRememberedEntityStore(): Unit =
    new RememberedEntityOperation().commit()
}
