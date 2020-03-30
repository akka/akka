/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.internal
import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.cluster.sharding.ClusterShardingSettings
import akka.cluster.sharding.ShardRegion.ShardId

/**
 * INTERNAL API
 *
 * Only intended for testing, not an extension point.
 */
private[akka] final class CustomStateStoreModeProvider(
    system: ActorSystem,
    typeName: String,
    settings: ClusterShardingSettings)
    extends RememberEntitiesShardStoreProvider {

  system.log.warning("Using custom remember entities store, not intended for production use.")

  override def createStoreForShard(shardId: ShardId): RememberEntitiesShardStore = {
    if (system.settings.config.hasPath("akka.cluster.sharding.custom-store")) {
      val customClassName = system.settings.config.getString("akka.cluster.sharding.custom-store")

      system.log.debug("Will use remember entities store [{}] for shard [{}]", customClassName, shardId)
      val store = system
        .asInstanceOf[ExtendedActorSystem]
        .dynamicAccess
        .createInstanceFor[RememberEntitiesShardStore](
          customClassName,
          Vector(
            (classOf[ActorSystem], system),
            (classOf[ClusterShardingSettings], settings),
            (classOf[String], typeName),
            (classOf[String], shardId)))

      system.log.debug("Created remember entities store [{}] for shard [{}]", store, shardId)

      store.get

    } else {
      system.log.error("Missing custom store class configuration for CustomStateStoreModeProvider")
      throw new RuntimeException("Missing custom store class configuration")
    }
  }
}
