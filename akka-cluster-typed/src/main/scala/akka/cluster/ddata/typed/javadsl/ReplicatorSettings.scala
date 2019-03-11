/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.ddata.typed.javadsl

import akka.cluster.{ ddata => dd }
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter._
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
