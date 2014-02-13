/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.datareplication

import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionIdProvider
import akka.actor.ExtensionId
import akka.actor.ActorSystem
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.actor.ActorRef
import akka.cluster.Cluster
import scala.concurrent.duration._

/**
 * Akka extension for convenient configuration and use of the
 * [[Replicator]]. Configuration settings are defined in the
 * `akka.contrib.datareplication` section, see `reference.conf`.
 */
object DataReplication extends ExtensionId[DataReplication] with ExtensionIdProvider {
  override def get(system: ActorSystem): DataReplication = super.get(system)

  override def lookup = DataReplication

  override def createExtension(system: ExtendedActorSystem): DataReplication =
    new DataReplication(system)
}

/**
 * @see [[DataReplication$ DataReplication companion object]]
 */
class DataReplication(system: ExtendedActorSystem) extends Extension {

  private val config = system.settings.config.getConfig("akka.contrib.datareplication")

  private val role: Option[String] = config.getString("role") match {
    case "" ⇒ None
    case r  ⇒ Some(r)
  }

  /**
   * Returns true if this member is not tagged with the role configured for the
   * replicas.
   */
  def isTerminated: Boolean = Cluster(system).isTerminated || !role.forall(Cluster(system).selfRoles.contains)

  /**
   * `ActorRef` of the [[Replicator]] .
   */
  val replicator: ActorRef =
    if (isTerminated)
      system.deadLetters
    else {
      val name = config.getString("name")
      val gossipInterval = config.getDuration("gossip-interval", MILLISECONDS).millis
      val maxDeltaElements = config.getInt("max-delta-elements")
      val pruningInterval = config.getDuration("pruning-interval", MILLISECONDS).millis
      val maxPruningDissemination = config.getDuration("max-pruning-dissemination", MILLISECONDS).millis

      system.actorOf(Replicator.props(role, gossipInterval, maxDeltaElements,
        pruningInterval, maxPruningDissemination), name)
    }
}