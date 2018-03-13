/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.ddata.typed.scaladsl

import akka.cluster.{ ddata ⇒ dd }
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.adapter._
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
