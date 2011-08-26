/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import collection.immutable.Seq

import java.util.concurrent.ConcurrentHashMap

import akka.event.EventHandler
import akka.actor.DeploymentConfig._
import akka.util.ReflectiveAccess._
import akka.AkkaException
import akka.config.{ Configuration, ConfigurationException, Config }

trait ActorDeployer {
  private[akka] def init(deployments: Seq[Deploy]): Unit
  private[akka] def shutdown(): Unit //TODO Why should we have "shutdown", should be crash only?
  private[akka] def deploy(deployment: Deploy): Unit
  private[akka] def lookupDeploymentFor(address: String): Option[Deploy]
  private[akka] def deploy(deployment: Seq[Deploy]): Unit = deployment foreach (deploy(_))
}

/**
 * Deployer maps actor deployments to actor addresses.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Deployer extends ActorDeployer {

  //  val defaultAddress = Node(Config.nodename)

  lazy val instance: ActorDeployer = {
    val deployer = if (ClusterModule.isEnabled) ClusterModule.clusterDeployer else LocalDeployer
    deployer.init(deploymentsInConfig)
    deployer
  }

  def start(): Unit = instance.toString //Force evaluation

  private[akka] def init(deployments: Seq[Deploy]) = instance.init(deployments)

  def shutdown(): Unit = instance.shutdown() //TODO Why should we have "shutdown", should be crash only?

  def deploy(deployment: Deploy): Unit = instance.deploy(deployment)

  def isLocal(deployment: Deploy): Boolean = deployment match {
    case Deploy(_, _, _, Local) | Deploy(_, _, _, _: Local) ⇒ true
    case _ ⇒ false
  }

  def isClustered(deployment: Deploy): Boolean = !isLocal(deployment)

  def isLocal(address: String): Boolean = isLocal(deploymentFor(address)) //TODO Should this throw exception if address not found?

  def isClustered(address: String): Boolean = !isLocal(address) //TODO Should this throw exception if address not found?

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
      val newDeployment = try {
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
  private[akka] def lookupInConfig(address: String, configuration: Configuration = Config.config): Option[Deploy] = {
    import akka.util.ReflectiveAccess.{ createInstance, emptyArguments, emptyParams, getClassFor }

    // --------------------------------
    // akka.actor.deployment.<address>
    // --------------------------------
    val addressPath = "akka.actor.deployment." + address
    configuration.getSection(addressPath) match {
      case None ⇒ Some(Deploy(address, None, Direct, Local))
      case Some(addressConfig) ⇒

        // --------------------------------
        // akka.actor.deployment.<address>.router
        // --------------------------------
        val router: Routing = addressConfig.getString("router", "direct") match {
          case "direct"         ⇒ Direct
          case "round-robin"    ⇒ RoundRobin
          case "random"         ⇒ Random
          case "least-cpu"      ⇒ LeastCPU
          case "least-ram"      ⇒ LeastRAM
          case "least-messages" ⇒ LeastMessages
          case customRouterClassName ⇒
            createInstance[AnyRef](customRouterClassName, emptyParams, emptyArguments).fold(
              e ⇒ throw new ConfigurationException(
                "Config option [" + addressPath + ".router] needs to be one of " +
                  "[\"direct\", \"round-robin\", \"random\", \"least-cpu\", \"least-ram\", \"least-messages\" or FQN of router class]", e),
              CustomRouter(_))
        }

        val recipe: Option[ActorRecipe] = addressConfig.getSection("create-as") map { section ⇒
          val implementationClass = section.getString("implementation-class") match {
            case Some(impl) ⇒
              getClassFor[Actor](impl).fold(e ⇒ throw new ConfigurationException("Config option [" + addressPath + ".create-as.implementation-class] load failed", e), identity)
            case None ⇒ throw new ConfigurationException("Config option [" + addressPath + ".create-as.implementation-class] is missing")
          }

          ActorRecipe(implementationClass)
        }

        // --------------------------------
        // akka.actor.deployment.<address>.clustered
        // --------------------------------
        addressConfig.getSection("clustered") match {
          case None ⇒
            Some(Deploy(address, recipe, router, Local)) // deploy locally

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
                    //case "host" ⇒ Host(address)
                    case "node" ⇒ Node(address)
                    //case "ip"   ⇒ IP(address)
                    case _      ⇒ raiseHomeConfigError()
                  }
                }
            }

            // --------------------------------
            // akka.actor.deployment.<address>.clustered.replicas
            // --------------------------------
            val replicationFactor = {
              if (router == Direct) new ReplicationFactor(1)
              else {
                clusteredConfig.getAny("replication-factor", "0") match {
                  case "auto" ⇒ AutoReplicationFactor
                  case "0"    ⇒ ZeroReplicationFactor
                  case nrOfReplicas: String ⇒
                    try {
                      new ReplicationFactor(nrOfReplicas.toInt)
                    } catch {
                      case e: Exception ⇒
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
                Some(Deploy(address, recipe, router, Clustered(preferredNodes, replicationFactor, Transient)))

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
                Some(Deploy(address, recipe, router, Clustered(preferredNodes, replicationFactor, Replication(storage, strategy))))
            }
        }
    }
  }

  private[akka] def throwDeploymentBoundException(deployment: Deploy): Nothing = {
    val e = new DeploymentAlreadyBoundException("Address [" + deployment.address + "] already bound to [" + deployment + "]")
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
object LocalDeployer extends ActorDeployer {
  private val deployments = new ConcurrentHashMap[String, Deploy]

  private[akka] def init(deployments: Seq[Deploy]) {
    EventHandler.info(this, "Deploying actors locally [\n\t%s\n]" format deployments.mkString("\n\t"))
    deployments foreach (deploy(_)) // deploy
  }

  private[akka] def shutdown() {
    deployments.clear() //TODO do something else/more?
  }

  private[akka] def deploy(deployment: Deploy) {
    deployments.putIfAbsent(deployment.address, deployment) /* match {
      case null ⇒
        deployment match {
          case Deploy(address, Some(recipe), routing, _) ⇒ Actor.actorOf(recipe.implementationClass, address).start() //FIXME use routing?
          case _                                         ⇒
        }
      case `deployment` ⇒ //Already deployed TODO should it be like this?
      case preexists    ⇒ Deployer.throwDeploymentBoundException(deployment)
    }*/
  }

  private[akka] def lookupDeploymentFor(address: String): Option[Deploy] = Option(deployments.get(address))
}

class DeploymentException private[akka] (message: String) extends AkkaException(message)
class DeploymentAlreadyBoundException private[akka] (message: String) extends AkkaException(message)
class NoDeploymentBoundException private[akka] (message: String) extends AkkaException(message)
