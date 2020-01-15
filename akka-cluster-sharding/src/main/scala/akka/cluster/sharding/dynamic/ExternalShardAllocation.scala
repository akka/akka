/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.dynamic

import java.util.concurrent.ConcurrentHashMap

import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.cluster.sharding.dynamic.internal.ExternalShardAllocationClientImpl
import java.util.function.{ Function => JFunction }

import akka.annotation.ApiMayChange

/**
 * API May Change
 */
@ApiMayChange
final class ExternalShardAllocation(system: ExtendedActorSystem) extends Extension {

  private val clients = new ConcurrentHashMap[String, ExternalShardAllocationClientImpl]

  private val factory = new JFunction[String, ExternalShardAllocationClientImpl] {
    override def apply(typeName: String): ExternalShardAllocationClientImpl =
      new ExternalShardAllocationClientImpl(system, typeName)
  }

  /**
   * Scala API
   */
  def clientFor(typeName: String): scaladsl.ExternalShardAllocationClient = client(typeName)

  /**
   * Java API
   */
  def getClient(typeName: String): javadsl.ExternalShardAllocationClient = client(typeName)

  private def client(typeName: String): ExternalShardAllocationClientImpl = {
    clients.computeIfAbsent(typeName, factory)
  }
}

object ExternalShardAllocation extends ExtensionId[ExternalShardAllocation] with ExtensionIdProvider {

  override def createExtension(system: ExtendedActorSystem): ExternalShardAllocation =
    new ExternalShardAllocation(system)

  override def lookup(): ExternalShardAllocation.type = ExternalShardAllocation

  override def get(system: ActorSystem): ExternalShardAllocation = super.get(system)
}
