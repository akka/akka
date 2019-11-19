/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.dynamic

import java.util.concurrent.ConcurrentHashMap

import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.cluster.sharding.dynamic.internal.DynamicShardAllocationClientImpl
import java.util.function.{ Function => JFunction }

// TODO typed extension that takes a EntityTypeKey
final class DynamicShardAllocation(system: ExtendedActorSystem) extends Extension {

  private val clients = new ConcurrentHashMap[String, DynamicShardAllocationClientImpl]

  private val factory = new JFunction[String, DynamicShardAllocationClientImpl] {
    override def apply(typeName: String): DynamicShardAllocationClientImpl =
      new DynamicShardAllocationClientImpl(system, typeName)
  }

  /**
   * Scala API
   */
  def clientFor(typeName: String): scaladsl.DynamicShardAllocationClient = client(typeName)

  /**
   * Java API
   */
  def getClient(typeName: String): javadsl.DynamicShardAllocationClient = client(typeName)

  private def client(typeName: String): DynamicShardAllocationClientImpl = {
    clients.computeIfAbsent(typeName, factory)
  }
}

object DynamicShardAllocation extends ExtensionId[DynamicShardAllocation] with ExtensionIdProvider {

  override def createExtension(system: ExtendedActorSystem): DynamicShardAllocation = new DynamicShardAllocation(system)

  override def lookup(): DynamicShardAllocation.type = DynamicShardAllocation

  override def get(system: ActorSystem): DynamicShardAllocation = super.get(system)
}
