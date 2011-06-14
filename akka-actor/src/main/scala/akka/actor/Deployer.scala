/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import collection.immutable.Seq

import java.util.concurrent.ConcurrentHashMap

import akka.event.EventHandler
import akka.actor.DeploymentConfig._
import akka.config.{ ConfigurationException, Config }
import akka.routing.RouterType
import akka.util.ReflectiveAccess._
import akka.serialization._
import akka.AkkaException

/**
 * Module holding the programmatic deployment configuration classes.
 * Defines the deployment specification.
 * Most values have defaults and can be left out.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object DeploymentConfig {

  // --------------------------------
  // --- Deploy
  // --------------------------------
  case class Deploy(
    address: String,
    routing: Routing = Direct,
    format: String = Serializer.defaultSerializerName, // Format.defaultSerializerName,
    scope: Scope = Local)

  // --------------------------------
  // --- Routing
  // --------------------------------
  sealed trait Routing
  case class CustomRouter(router: AnyRef) extends Routing

  // For Java API
  case class Direct() extends Routing
  case class RoundRobin() extends Routing
  case class Random() extends Routing
  case class LeastCPU() extends Routing
  case class LeastRAM() extends Routing
  case class LeastMessages() extends Routing

  // For Scala API
  case object Direct extends Routing
  case object RoundRobin extends Routing
  case object Random extends Routing
  case object LeastCPU extends Routing
  case object LeastRAM extends Routing
  case object LeastMessages extends Routing

  // --------------------------------
  // --- Scope
  // --------------------------------
  sealed trait Scope
  case class Clustered(
    home: Home = Host("localhost"),
    replicas: Replicas = NoReplicas,
    replication: ReplicationScheme = Transient) extends Scope

  // For Java API
  case class Local() extends Scope

  // For Scala API
  case object Local extends Scope

  // --------------------------------
  // --- Home
  // --------------------------------
  sealed trait Home
  case class Host(hostName: String) extends Home
  case class Node(nodeName: String) extends Home
  case class IP(ipAddress: String) extends Home

  // --------------------------------
  // --- Replicas
  // --------------------------------
  sealed trait Replicas
  case class Replicate(factor: Int) extends Replicas {
    if (factor < 1) throw new IllegalArgumentException("Replicas factor can not be negative or zero")
  }

  // For Java API
  case class AutoReplicate() extends Replicas
  case class NoReplicas() extends Replicas

  // For Scala API
  case object AutoReplicate extends Replicas
  case object NoReplicas extends Replicas

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
  case class WriteBehind() extends ReplicationStrategy
  case class WriteThrough() extends ReplicationStrategy

  // For Scala API
  case object WriteBehind extends ReplicationStrategy
  case object WriteThrough extends ReplicationStrategy

  // --------------------------------
  // --- Helper methods for parsing
  // --------------------------------

  def isHomeNode(home: Home): Boolean = home match {
    case Host(hostname) ⇒ hostname == Config.hostname
    case IP(address)    ⇒ address == "0.0.0.0" || address == "127.0.0.1" // FIXME look up IP address from the system
    case Node(nodename) ⇒ nodename == Config.nodename
  }

  def replicaValueFor(replicas: Replicas): Int = replicas match {
    case Replicate(replicas) ⇒ replicas
    case AutoReplicate       ⇒ -1
    case AutoReplicate()     ⇒ -1
    case NoReplicas          ⇒ 0
    case NoReplicas()        ⇒ 0
  }

  def routerTypeFor(routing: Routing): RouterType = routing match {
    case Direct          ⇒ RouterType.Direct
    case Direct()        ⇒ RouterType.Direct
    case RoundRobin      ⇒ RouterType.RoundRobin
    case RoundRobin()    ⇒ RouterType.RoundRobin
    case Random          ⇒ RouterType.Random
    case Random()        ⇒ RouterType.Random
    case LeastCPU        ⇒ RouterType.LeastCPU
    case LeastCPU()      ⇒ RouterType.LeastCPU
    case LeastRAM        ⇒ RouterType.LeastRAM
    case LeastRAM()      ⇒ RouterType.LeastRAM
    case LeastMessages   ⇒ RouterType.LeastMessages
    case LeastMessages() ⇒ RouterType.LeastMessages
    case c: CustomRouter ⇒ throw new UnsupportedOperationException("routerTypeFor: " + c)
  }

  def isReplicationAsync(strategy: ReplicationStrategy): Boolean = strategy match {
    case _: WriteBehind | WriteBehind   ⇒ true
    case _: WriteThrough | WriteThrough ⇒ false
  }
}

/**
 * Deployer maps actor deployments to actor addresses.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Deployer {

  val defaultAddress = Host(Config.hostname)

  lazy val instance: ClusterModule.ClusterDeployer = {
    val deployer =
      if (ClusterModule.isEnabled) ClusterModule.clusterDeployer
      else LocalDeployer
    deployer.init(deploymentsInConfig)
    deployer
  }

  def start() {
    instance.toString
  }

  def shutdown() {
    instance.shutdown()
  }

  def deploy(deployment: Deploy) {
    if (deployment eq null) throw new IllegalArgumentException("Deploy can not be null")
    val address = deployment.address
    Address.validate(address)
    instance.deploy(deployment)
  }

  def deploy(deployment: Seq[Deploy]) {
    deployment foreach (deploy(_))
  }

  /**
   * Undeploy is idemponent. E.g. safe to invoke multiple times.
   */
  def undeploy(deployment: Deploy) {
    instance.undeploy(deployment)
  }

  def undeployAll() {
    instance.undeployAll()
  }

  def isLocal(deployment: Deploy): Boolean = deployment match {
    case Deploy(_, _, _, Local) ⇒ true
    case _                      ⇒ false
  }

  def isClustered(deployment: Deploy): Boolean = isLocal(deployment)

  def isLocal(address: String): Boolean = isLocal(deploymentFor(address))

  def isClustered(address: String): Boolean = !isLocal(address)

  /**
   * Same as 'lookupDeploymentFor' but throws an exception if no deployment is bound.
   */
  private[akka] def deploymentFor(address: String): Deploy = {
    lookupDeploymentFor(address) match {
      case Some(deployment) ⇒ deployment
      case None             ⇒ thrownNoDeploymentBoundException(address)
    }
  }

  private[akka] def lookupDeploymentFor(address: String): Option[Deploy] = {
    val deployment_? = instance.lookupDeploymentFor(address)
    if (deployment_?.isDefined && (deployment_?.get ne null)) deployment_?
    else {
      val newDeployment =
        try {
          lookupInConfig(address)
        } catch {
          case e: ConfigurationException ⇒
            EventHandler.error(e, this, e.getMessage)
            throw e
        }
      newDeployment foreach { d ⇒
        if (d eq null) {
          val e = new IllegalStateException("Deployment for address [" + address + "] is null")
          EventHandler.error(e, this, e.getMessage)
          throw e
        }
        deploy(d) // deploy and cache it
      }
      newDeployment
    }
  }

  private[akka] def deploymentsInConfig: List[Deploy] = {
    for {
      address ← addressesInConfig
      deployment ← lookupInConfig(address)
    } yield deployment
  }

  private[akka] def addressesInConfig: List[String] = {
    val deploymentPath = "akka.actor.deployment"
    Config.config.getSection(deploymentPath) match {
      case None ⇒ Nil
      case Some(addressConfig) ⇒
        addressConfig.map.keySet
          .map(path ⇒ path.substring(0, path.indexOf(".")))
          .toSet.toList // toSet to force uniqueness
    }
  }

  /**
   * Lookup deployment in 'akka.conf' configuration file.
   */
  private[akka] def lookupInConfig(address: String): Option[Deploy] = {

    // --------------------------------
    // akka.actor.deployment.<address>
    // --------------------------------
    val addressPath = "akka.actor.deployment." + address
    Config.config.getSection(addressPath) match {
      case None ⇒ Some(Deploy(address, Direct, Serializer.defaultSerializerName, Local))
      case Some(addressConfig) ⇒

        // --------------------------------
        // akka.actor.deployment.<address>.router
        // --------------------------------
        val router = addressConfig.getString("router", "direct") match {
          case "direct"         ⇒ Direct
          case "round-robin"    ⇒ RoundRobin
          case "random"         ⇒ Random
          case "least-cpu"      ⇒ LeastCPU
          case "least-ram"      ⇒ LeastRAM
          case "least-messages" ⇒ LeastMessages
          case customRouterClassName ⇒
            val customRouter = try {
              Class.forName(customRouterClassName).newInstance.asInstanceOf[AnyRef]
            } catch {
              case e ⇒ throw new ConfigurationException(
                "Config option [" + addressPath + ".router] needs to be one of " +
                  "[\"direct\", \"round-robin\", \"random\", \"least-cpu\", \"least-ram\", \"least-messages\" or FQN of router class]")
            }
            CustomRouter(customRouter)
        }

        // --------------------------------
        // akka.actor.deployment.<address>.format
        // --------------------------------
        val format = addressConfig.getString("format", Serializer.defaultSerializerName)

        // --------------------------------
        // akka.actor.deployment.<address>.clustered
        // --------------------------------
        addressConfig.getSection("clustered") match {
          case None ⇒
            Some(Deploy(address, router, Serializer.defaultSerializerName, Local)) // deploy locally

          case Some(clusteredConfig) ⇒

            // --------------------------------
            // akka.actor.deployment.<address>.clustered.home
            // --------------------------------

            val home = clusteredConfig.getString("home", "") match {
              case "" ⇒ Host("localhost")
              case home ⇒
                def raiseHomeConfigError() = throw new ConfigurationException(
                  "Config option [" + addressPath +
                    ".clustered.home] needs to be on format 'host:<hostname>', 'ip:<ip address>'' or 'node:<node name>', was [" +
                    home + "]")

                if (!(home.startsWith("host:") || home.startsWith("node:") || home.startsWith("ip:"))) raiseHomeConfigError()

                val tokenizer = new java.util.StringTokenizer(home, ":")
                val protocol = tokenizer.nextElement
                val address = tokenizer.nextElement.asInstanceOf[String]

                protocol match {
                  case "host" ⇒ Host(address)
                  case "node" ⇒ Node(address)
                  case "ip"   ⇒ IP(address)
                  case _      ⇒ raiseHomeConfigError()
                }
            }

            // --------------------------------
            // akka.actor.deployment.<address>.clustered.replicas
            // --------------------------------
            val replicas = clusteredConfig.getAny("replicas", "0") match {
              case "auto" ⇒ AutoReplicate
              case "0"    ⇒ NoReplicas
              case nrOfReplicas: String ⇒
                try {
                  Replicate(nrOfReplicas.toInt)
                } catch {
                  case e: NumberFormatException ⇒
                    throw new ConfigurationException(
                      "Config option [" + addressPath +
                        ".clustered.replicas] needs to be either [\"auto\"] or [0-N] - was [" +
                        nrOfReplicas + "]")
                }
            }

            // --------------------------------
            // akka.actor.deployment.<address>.clustered.replication
            // --------------------------------
            clusteredConfig.getSection("replication") match {
              case None ⇒
                Some(Deploy(address, router, format, Clustered(home, replicas, Transient)))

              case Some(replicationConfig) ⇒
                val storage = replicationConfig.getString("storage", "transaction-log") match {
                  case "transaction-log" ⇒ TransactionLog
                  case "data-grid"       ⇒ DataGrid
                  case unknown ⇒
                    throw new ConfigurationException("Config option [" + addressPath +
                      ".clustered.replication.storage] needs to be either [\"transaction-log\"] or [\"data-grid\"] - was [" +
                      unknown + "]")
                }
                val strategy = replicationConfig.getString("strategy", "write-through") match {
                  case "write-through" ⇒ WriteThrough
                  case "write-behind"  ⇒ WriteBehind
                  case unknown ⇒
                    throw new ConfigurationException("Config option [" + addressPath +
                      ".clustered.replication.strategy] needs to be either [\"write-through\"] or [\"write-behind\"] - was [" +
                      unknown + "]")
                }
                Some(Deploy(address, router, format, Clustered(home, replicas, Replication(storage, strategy))))
            }
        }
    }
  }

  private[akka] def throwDeploymentBoundException(deployment: Deploy): Nothing = {
    val e = new DeploymentAlreadyBoundException(
      "Address [" + deployment.address +
        "] already bound to [" + deployment +
        "]. You have to invoke 'undeploy(deployment) first.")
    EventHandler.error(e, this, e.getMessage)
    throw e
  }

  private[akka] def thrownNoDeploymentBoundException(address: String): Nothing = {
    val e = new NoDeploymentBoundException("Address [" + address + "] is not bound to a deployment")
    EventHandler.error(e, this, e.getMessage)
    throw e
  }
}

/**
 * TODO: Improved documentation
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object LocalDeployer {
  private val deployments = new ConcurrentHashMap[String, Deploy]

  private[akka] def init(deployments: List[Deploy]) {
    EventHandler.info(this, "Deploying actors locally [\n\t%s\n]" format deployments.mkString("\n\t"))
    deployments foreach (deploy(_)) // deploy
  }

  private[akka] def shutdown() {
    undeployAll()
    deployments.clear()
  }

  private[akka] def deploy(deployment: Deploy) {
    if (deployments.putIfAbsent(deployment.address, deployment) != deployment) {
      //Deployer.throwDeploymentBoundException(deployment) // FIXME uncomment this and fix the issue with multiple deployments
    }
  }

  private[akka] def undeploy(deployment: Deploy): Unit = deployments.remove(deployment.address)

  private[akka] def undeployAll(): Unit = deployments.clear()

  private[akka] def lookupDeploymentFor(address: String): Option[Deploy] = Option(deployments.get(address))
}

/**
 * TODO: Improved documentation
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Address {
  private val validAddressPattern = java.util.regex.Pattern.compile("[0-9a-zA-Z\\-\\_\\$\\.]+")

  def validate(address: String) {
    if (validAddressPattern.matcher(address).matches) true
    else {
      val e = new IllegalArgumentException(
        "Address [" + address + "] is not valid, need to follow pattern [0-9a-zA-Z\\-\\_\\$]+")
      EventHandler.error(e, this, e.getMessage)
      throw e
    }
  }
}

class DeploymentException private[akka] (message: String) extends AkkaException(message)
class DeploymentAlreadyBoundException private[akka] (message: String) extends AkkaException(message)
class NoDeploymentBoundException private[akka] (message: String) extends AkkaException(message)
