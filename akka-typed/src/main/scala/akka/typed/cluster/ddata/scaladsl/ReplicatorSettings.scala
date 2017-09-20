/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.cluster.ddata.scaladsl

import akka.cluster.{ ddata ⇒ dd }
import akka.typed.ActorSystem
import akka.typed.scaladsl.adapter._
import com.typesafe.config.Config

/**
 * @see [[akka.cluster.ddata.ReplicatorSettings]].
 */
object ReplicatorSettings {
  /**
   * Create settings from the default configuration
   * `akka.cluster.distributed-data`.
   */
  def apply(system: ActorSystem[_]): ReplicatorSettings =
    dd.ReplicatorSettings(system.toUntyped)

  /**
   * Create settings from a configuration with the same layout as
   * the default configuration `akka.cluster.distributed-data`.
   */
  def apply(config: Config): ReplicatorSettings =
    dd.ReplicatorSettings(config)
}
