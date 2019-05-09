/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import akka.ConfigurationException
import akka.actor.{ ActorSystem, ExtendedActorSystem, Props }
import com.github.ghik.silencer.silent

import scala.concurrent.duration.FiniteDuration

/**
 * INTERNAL API
 */
private[cluster] object DowningProvider {

  /**
   * @param fqcn Fully qualified class name of the implementation to be loaded.
   * @param system Actor system used to load the implemntation
   * @return the provider or throws a [[akka.ConfigurationException]] if loading it fails
   */
  def load(fqcn: String, system: ActorSystem): DowningProvider = {
    val eas = system.asInstanceOf[ExtendedActorSystem]
    eas.dynamicAccess
      .createInstanceFor[DowningProvider](fqcn, List((classOf[ActorSystem], system)))
      .recover {
        case e => throw new ConfigurationException(s"Could not create cluster downing provider [$fqcn]", e)
      }
      .get
  }

}

/**
 * API for plugins that will handle downing of cluster nodes. Concrete plugins must subclass and
 * have a public one argument constructor accepting an [[akka.actor.ActorSystem]].
 */
abstract class DowningProvider {

  /**
   * Time margin after which shards or singletons that belonged to a downed/removed
   * partition are created in surviving partition. The purpose of this margin is that
   * in case of a network partition the persistent actors in the non-surviving partitions
   * must be stopped before corresponding persistent actors are started somewhere else.
   * This is useful if you implement downing strategies that handle network partitions,
   * e.g. by keeping the larger side of the partition and shutting down the smaller side.
   */
  def downRemovalMargin: FiniteDuration

  /**
   * If a props is returned it is created as a child of the core cluster daemon on cluster startup.
   * It should then handle downing using the regular [[akka.cluster.Cluster]] APIs.
   * The actor will run on the same dispatcher as the cluster actor if dispatcher not configured.
   *
   * May throw an exception which will then immediately lead to Cluster stopping, as the downing
   * provider is vital to a working cluster.
   */
  def downingActorProps: Option[Props]

}

/**
 * Default downing provider used when no provider is configured and 'auto-down-unreachable-after'
 * is not enabled.
 */
final class NoDowning(system: ActorSystem) extends DowningProvider {
  @silent
  override def downRemovalMargin: FiniteDuration = Cluster(system).settings.DownRemovalMargin
  override val downingActorProps: Option[Props] = None
}
