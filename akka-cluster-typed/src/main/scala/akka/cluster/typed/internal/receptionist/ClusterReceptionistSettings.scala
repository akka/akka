/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.typed.internal.receptionist

import akka.actor.typed.ActorSystem
import akka.annotation.InternalApi
import akka.cluster.ddata.Replicator
import akka.cluster.ddata.Replicator.WriteConsistency
import akka.util.Helpers.toRootLowerCase
import com.typesafe.config.Config

import scala.concurrent.duration._
import scala.concurrent.duration.{ FiniteDuration, MILLISECONDS }

/**
 * Internal API
 */
@InternalApi
private[akka] object ClusterReceptionistSettings {
  def apply(system: ActorSystem[_]): ClusterReceptionistSettings =
    apply(system.settings.config.getConfig("akka.cluster.typed.receptionist"))

  def apply(config: Config): ClusterReceptionistSettings = {
    val writeTimeout = 5.seconds // the timeout is not important
    val writeConsistency = {
      val key = "write-consistency"
      toRootLowerCase(config.getString(key)) match {
        case "local"    ⇒ Replicator.WriteLocal
        case "majority" ⇒ Replicator.WriteMajority(writeTimeout)
        case "all"      ⇒ Replicator.WriteAll(writeTimeout)
        case _          ⇒ Replicator.WriteTo(config.getInt(key), writeTimeout)
      }
    }
    ClusterReceptionistSettings(
      writeConsistency,
      pruningInterval = config.getDuration("pruning-interval", MILLISECONDS).millis,
      config.getInt("distributed-key-count")
    )
  }
}

/**
 * Internal API
 */
@InternalApi
private[akka] case class ClusterReceptionistSettings(
  writeConsistency:    WriteConsistency,
  pruningInterval:     FiniteDuration,
  distributedKeyCount: Int)

