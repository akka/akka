/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import collection.immutable.Seq

import java.util.concurrent.ConcurrentHashMap

import akka.event.EventHandler
import akka.actor.DeploymentConfig._
import akka.config.{ ConfigurationException, Config }
import akka.util.ReflectiveAccess._
import akka.AkkaException

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
    case Deploy(_, _, Local) ⇒ true
    case _                   ⇒ false
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
      case None ⇒ Some(Deploy(address, Direct, Local))
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
        // akka.actor.deployment.<address>.clustered
        // --------------------------------
        addressConfig.getSection("clustered") match {
          case None ⇒
            Some(Deploy(address, router, Local)) // deploy locally

          case Some(clusteredConfig) ⇒

            // --------------------------------
            // akka.actor.deployment.<address>.clustered.preferred-nodes
            // --------------------------------

            val preferredNodes = clusteredConfig.getList("preferred-nodes") match {
              case Nil ⇒ Nil
              case homes ⇒
                def raiseHomeConfigError() = throw new ConfigurationException(
                  "Config option [" + addressPath +
                    ".clustered.preferred-nodes] needs to be a list with elements on format\n'host:<hostname>', 'ip:<ip address>' or 'node:<node name>', was [" +
                    homes + "]")

                homes map { home ⇒
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
            }

            // --------------------------------
            // akka.actor.deployment.<address>.clustered.replicas
            // --------------------------------
            val replicationFactor = {
              if (router == Direct) ReplicationFactor(1)
              else {
                clusteredConfig.getAny("replication-factor", "0") match {
                  case "auto" ⇒ AutoReplicationFactor
                  case "0"    ⇒ ZeroReplicationFactor
                  case nrOfReplicas: String ⇒
                    try {
                      ReplicationFactor(nrOfReplicas.toInt)
                    } catch {
                      case e: NumberFormatException ⇒
                        throw new ConfigurationException(
                          "Config option [" + addressPath +
                            ".clustered.replicas] needs to be either [\"auto\"] or [0-N] - was [" +
                            nrOfReplicas + "]")
                    }
                }
              }
            }

            // --------------------------------
            // akka.actor.deployment.<address>.clustered.replication
            // --------------------------------
            clusteredConfig.getSection("replication") match {
              case None ⇒
                Some(Deploy(address, router, Clustered(preferredNodes, replicationFactor, Transient)))

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
                Some(Deploy(address, router, Clustered(preferredNodes, replicationFactor, Replication(storage, strategy))))
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

  def validate(address: String): Unit =
    if (!validAddressPattern.matcher(address).matches) {
      val e = new IllegalArgumentException("Address [" + address + "] is not valid, need to follow pattern: " + validAddressPattern.pattern)
      EventHandler.error(e, this, e.getMessage)
      throw e
    }
}

class DeploymentException private[akka] (message: String) extends AkkaException(message)
class DeploymentAlreadyBoundException private[akka] (message: String) extends AkkaException(message)
class NoDeploymentBoundException private[akka] (message: String) extends AkkaException(message)
