/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.AkkaApplication
import akka.util.Duration
import akka.routing.{ RouterType, FailureDetectorType }
import akka.routing.FailureDetectorType._

object DeploymentConfig {

  // --------------------------------
  // --- Deploy
  // --------------------------------
  case class Deploy(
    path: String,
    recipe: Option[ActorRecipe],
    routing: Routing = Direct,
    nrOfInstances: NrOfInstances = ZeroNrOfInstances,
    scope: Scope = LocalScope)

  // --------------------------------
  // --- Actor Recipe
  // --------------------------------
  case class ActorRecipe(implementationClass: Class[_ <: Actor]) //TODO Add ActorConfiguration here

  // --------------------------------
  // --- Routing
  // --------------------------------
  sealed trait Routing
  case class CustomRouter(routerClassName: String) extends Routing

  // For Java API
  case class Direct() extends Routing
  case class RoundRobin() extends Routing
  case class Random() extends Routing
  case class ScatterGather() extends Routing
  case class LeastCPU() extends Routing
  case class LeastRAM() extends Routing
  case class LeastMessages() extends Routing

  // For Scala API
  case object Direct extends Routing
  case object RoundRobin extends Routing
  case object Random extends Routing
  case object ScatterGather extends Routing
  case object LeastCPU extends Routing
  case object LeastRAM extends Routing
  case object LeastMessages extends Routing

  // --------------------------------
  // --- Scope
  // --------------------------------
  sealed trait Scope

  // For Java API
  case class LocalScope() extends Scope

  // For Scala API
  case object LocalScope extends Scope

  case class RemoteScope(nodes: Iterable[RemoteAddress]) extends Scope

  case class RemoteAddress(hostname: String, port: Int)

  // --------------------------------
  // --- Home
  // --------------------------------
  sealed trait Home
  //  case class Host(hostName: String) extends Home
  case class Node(nodeName: String) extends Home
  //  case class IP(ipAddress: String) extends Home

  // --------------------------------
  // --- Replicas
  // --------------------------------

  class NrOfInstances(val factor: Int) extends Serializable {
    if (factor < 0) throw new IllegalArgumentException("nr-of-instances can not be negative")
    override def hashCode = 0 + factor.##
    override def equals(other: Any) = NrOfInstances.unapply(this) == NrOfInstances.unapply(other)
    override def toString = "NrOfInstances(" + factor + ")"
  }

  object NrOfInstances {
    def apply(factor: Int): NrOfInstances = factor match {
      case -1 ⇒ AutoNrOfInstances
      case 0  ⇒ ZeroNrOfInstances
      case 1  ⇒ OneNrOfInstances
      case x  ⇒ new NrOfInstances(x)
    }
    def unapply(other: Any) = other match {
      case x: NrOfInstances ⇒ import x._; Some(factor)
      case _                ⇒ None
    }
  }

  // For Java API
  class AutoNrOfInstances extends NrOfInstances(-1)
  class ZeroNrOfInstances extends NrOfInstances(0)
  class OneNrOfInstances extends NrOfInstances(0)

  // For Scala API
  case object AutoNrOfInstances extends AutoNrOfInstances
  case object ZeroNrOfInstances extends ZeroNrOfInstances
  case object OneNrOfInstances extends OneNrOfInstances

  // --------------------------------
  // --- Replication
  // --------------------------------
  sealed trait ReplicationScheme

  // For Java API
  case class Transient() extends ReplicationScheme

  // For Scala API
  case object Transient extends ReplicationScheme
  case class Replication(
    storage: ReplicationStorage,
    strategy: ReplicationStrategy) extends ReplicationScheme

  // --------------------------------
  // --- ReplicationStorage
  // --------------------------------
  sealed trait ReplicationStorage

  // For Java API
  case class TransactionLog() extends ReplicationStorage
  case class DataGrid() extends ReplicationStorage

  // For Scala API
  case object TransactionLog extends ReplicationStorage
  case object DataGrid extends ReplicationStorage

  // --------------------------------
  // --- ReplicationStrategy
  // --------------------------------
  sealed trait ReplicationStrategy

  // For Java API
  sealed class WriteBehind extends ReplicationStrategy
  sealed class WriteThrough extends ReplicationStrategy

  // For Scala API
  case object WriteBehind extends WriteBehind
  case object WriteThrough extends WriteThrough

  // --------------------------------
  // --- Helper methods for parsing
  // --------------------------------

  def nodeNameFor(home: Home): String = home match {
    case Node(nodename) ⇒ nodename
    //    case Host("localhost") ⇒ Config.nodename
    //    case IP("0.0.0.0")     ⇒ Config.nodename
    //    case IP("127.0.0.1")   ⇒ Config.nodename
    //    case Host(hostname)    ⇒ throw new UnsupportedOperationException("Specifying preferred node name by 'hostname' is not yet supported. Use the node name like: preferred-nodes = [\"node:node1\"]")
    //    case IP(address)       ⇒ throw new UnsupportedOperationException("Specifying preferred node name by 'IP address' is not yet supported. Use the node name like: preferred-nodes = [\"node:node1\"]")
  }

  def routerTypeFor(routing: Routing): RouterType = routing match {
    case _: Direct | Direct               ⇒ RouterType.Direct
    case _: RoundRobin | RoundRobin       ⇒ RouterType.RoundRobin
    case _: Random | Random               ⇒ RouterType.Random
    case _: ScatterGather | ScatterGather ⇒ RouterType.ScatterGather
    case _: LeastCPU | LeastCPU           ⇒ RouterType.LeastCPU
    case _: LeastRAM | LeastRAM           ⇒ RouterType.LeastRAM
    case _: LeastMessages | LeastMessages ⇒ RouterType.LeastMessages
    case CustomRouter(implClass)          ⇒ RouterType.Custom(implClass)
  }

  def isReplicated(replicationScheme: ReplicationScheme): Boolean =
    isReplicatedWithTransactionLog(replicationScheme) ||
      isReplicatedWithDataGrid(replicationScheme)

  def isWriteBehindReplication(replicationScheme: ReplicationScheme): Boolean = replicationScheme match {
    case _: Transient | Transient ⇒ false
    case Replication(_, strategy) ⇒
      strategy match {
        case _: WriteBehind | WriteBehind   ⇒ true
        case _: WriteThrough | WriteThrough ⇒ false
      }
  }

  def isWriteThroughReplication(replicationScheme: ReplicationScheme): Boolean = replicationScheme match {
    case _: Transient | Transient ⇒ false
    case Replication(_, strategy) ⇒
      strategy match {
        case _: WriteBehind | WriteBehind   ⇒ true
        case _: WriteThrough | WriteThrough ⇒ false
      }
  }

  def isReplicatedWithTransactionLog(replicationScheme: ReplicationScheme): Boolean = replicationScheme match {
    case _: Transient | Transient ⇒ false
    case Replication(storage, _) ⇒
      storage match {
        case _: TransactionLog | TransactionLog ⇒ true
        case _: DataGrid | DataGrid             ⇒ throw new UnsupportedOperationException("ReplicationStorage 'DataGrid' is no supported yet")
      }
  }

  def isReplicatedWithDataGrid(replicationScheme: ReplicationScheme): Boolean = replicationScheme match {
    case _: Transient | Transient ⇒ false
    case Replication(storage, _) ⇒
      storage match {
        case _: TransactionLog | TransactionLog ⇒ false
        case _: DataGrid | DataGrid             ⇒ throw new UnsupportedOperationException("ReplicationStorage 'DataGrid' is no supported yet")
      }
  }

}

/**
 * Module holding the programmatic deployment configuration classes.
 * Defines the deployment specification.
 * Most values have defaults and can be left out.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class DeploymentConfig(val app: AkkaApplication) {

  import DeploymentConfig._

  case class ClusterScope(preferredNodes: Iterable[Home] = Vector(Node(app.nodename)), replication: ReplicationScheme = Transient) extends Scope

  def isHomeNode(homes: Iterable[Home]): Boolean = homes exists (home ⇒ nodeNameFor(home) == app.nodename)

  def replicationSchemeFor(deployment: Deploy): Option[ReplicationScheme] = deployment match {
    case Deploy(_, _, _, _, ClusterScope(_, replicationScheme)) ⇒ Some(replicationScheme)
    case _ ⇒ None
  }

  def isReplicated(deployment: Deploy): Boolean = replicationSchemeFor(deployment) match {
    case Some(replicationScheme) ⇒ DeploymentConfig.isReplicated(replicationScheme)
    case _                       ⇒ false
  }

}
