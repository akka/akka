/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.cluster.ddata.javadsl

import akka.cluster.{ ddata ⇒ dd }
import akka.typed.ActorSystem
import akka.typed.scaladsl.adapter._
import com.typesafe.config.Config

object ReplicatorSettings {
  /**
   * Create settings from the default configuration
   * `akka.cluster.distributed-data`.
   */
  def create(system: ActorSystem[_]): dd.ReplicatorSettings =
    dd.ReplicatorSettings(system.toUntyped)

  /**
   * Create settings from a configuration with the same layout as
   * the default configuration `akka.cluster.distributed-data`.
   */
  def create(config: Config): dd.ReplicatorSettings =
    dd.ReplicatorSettings(config)
}
