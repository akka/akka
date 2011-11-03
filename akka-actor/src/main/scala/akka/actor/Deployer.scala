/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import collection.immutable.Seq

import java.util.concurrent.ConcurrentHashMap

import akka.event.EventHandler
import akka.actor.DeploymentConfig._
import akka.{ AkkaException, AkkaApplication }
import akka.config.{ Configuration, ConfigurationException }
import akka.util.Duration
import java.net.InetSocketAddress

trait ActorDeployer {
  private[akka] def init(deployments: Seq[Deploy]): Unit
  private[akka] def shutdown(): Unit //TODO Why should we have "shutdown", should be crash only?
  private[akka] def deploy(deployment: Deploy): Unit
  private[akka] def lookupDeploymentFor(address: String): Option[Deploy]
  def lookupDeployment(address: String): Option[Deploy] = address match {
    case null | Props.`randomAddress` ⇒ None
    case some                         ⇒ lookupDeploymentFor(some)
  }
  private[akka] def deploy(deployment: Seq[Deploy]): Unit = deployment foreach (deploy(_))
}

/**
 * Deployer maps actor deployments to actor addresses.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class Deployer(val app: AkkaApplication) extends ActorDeployer {

  val deploymentConfig = new DeploymentConfig(app)

  lazy val instance: ActorDeployer = {
    val deployer = if (app.reflective.ClusterModule.isEnabled) app.reflective.ClusterModule.clusterDeployer else LocalDeployer
    deployer.init(deploymentsInConfig)
    deployer
  }

  def start(): Unit = instance.toString //Force evaluation

  private[akka] def init(deployments: Seq[Deploy]) = instance.init(deployments)

  def shutdown(): Unit = instance.shutdown() //TODO FIXME Why should we have "shutdown", should be crash only?

  def deploy(deployment: Deploy): Unit = instance.deploy(deployment)

  def isLocal(deployment: Deploy): Boolean = deployment match {
    case Deploy(_, _, _, _, _, LocalScope) | Deploy(_, _, _, _, _, _: LocalScope) ⇒ true
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
    instance.lookupDeploymentFor(address) match {
      case s @ Some(d) if d ne null ⇒ s
      case _ ⇒
        lookupInConfig(address) match {
          case None | Some(null) ⇒ None
          case s @ Some(d)       ⇒ deploy(d); s // deploy and cache it
        }
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
    app.config.getSection(deploymentPath) match {
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
  private[akka] def lookupInConfig(address: String, configuration: Configuration = app.config): Option[Deploy] = {
    import akka.util.ReflectiveAccess.{ createInstance, emptyArguments, emptyParams, getClassFor }

    // --------------------------------
    // akka.actor.deployment.<address>
    // --------------------------------
    val addressPath = "akka.actor.deployment." + address
    configuration.getSection(addressPath) match {
      case None ⇒ None
      case Some(addressConfig) ⇒

        // --------------------------------
        // akka.actor.deployment.<address>.router
        // --------------------------------
        val router: Routing = addressConfig.getString("router", "direct") match {
          case "direct"         ⇒ Direct
          case "round-robin"    ⇒ RoundRobin
          case "random"         ⇒ Random
          case "scatter-gather" ⇒ ScatterGather
          case "least-cpu"      ⇒ LeastCPU
          case "least-ram"      ⇒ LeastRAM
          case "least-messages" ⇒ LeastMessages
          case routerClassName  ⇒ CustomRouter(routerClassName)
        }

        // --------------------------------
        // akka.actor.deployment.<address>.nr-of-instances
        // --------------------------------
        val nrOfInstances = {
          if (router == Direct) OneNrOfInstances
          else {
            addressConfig.getAny("nr-of-instances", "1") match {
              case "auto" ⇒ AutoNrOfInstances
              case "1"    ⇒ OneNrOfInstances
              case "0"    ⇒ ZeroNrOfInstances
              case nrOfReplicas: String ⇒
                try {
                  new NrOfInstances(nrOfReplicas.toInt)
                } catch {
                  case e: Exception ⇒
                    throw new ConfigurationException(
                      "Config option [" + addressPath +
                        ".nr-of-instances] needs to be either [\"auto\"] or [1-N] - was [" +
                        nrOfReplicas + "]")
                }
            }
          }
        }

        // --------------------------------
        // akka.actor.deployment.<address>.failure-detector.<detector>
        // --------------------------------
        val failureDetectorOption: Option[FailureDetector] = addressConfig.getSection("failure-detector") match {
          case Some(failureDetectorConfig) ⇒
            failureDetectorConfig.keys.toList match {
              case Nil ⇒ None
              case detector :: Nil ⇒
                detector match {
                  case "no-op" ⇒
                    Some(NoOpFailureDetector)

                  case "remove-connection-on-first-failure" ⇒
                    Some(RemoveConnectionOnFirstFailureFailureDetector)

                  case "bannage-period" ⇒
                    throw new ConfigurationException(
                      "Configuration for [" + addressPath + ".failure-detector.bannage-period] must have a 'time-to-ban' option defined")

                  case "bannage-period.time-to-ban" ⇒
                    failureDetectorConfig.getSection("bannage-period") map { section ⇒
                      val timeToBan = Duration(section.getInt("time-to-ban", 60), app.AkkaConfig.DefaultTimeUnit)
                      BannagePeriodFailureDetector(timeToBan)
                    }

                  case "custom" ⇒
                    failureDetectorConfig.getSection("custom") map { section ⇒
                      val implementationClass = section.getString("class").getOrElse(throw new ConfigurationException(
                        "Configuration for [" + addressPath +
                          ".failure-detector.custom] must have a 'class' element with the fully qualified name of the failure detector class"))
                      CustomFailureDetector(implementationClass)
                    }

                  case _ ⇒ None
                }
              case detectors ⇒
                throw new ConfigurationException(
                  "Configuration for [" + addressPath +
                    ".failure-detector] can not have multiple sections - found [" + detectors.mkString(", ") + "]")
            }
          case None ⇒ None
        }
        val failureDetector = failureDetectorOption getOrElse { NoOpFailureDetector } // fall back to default failure detector

        // --------------------------------
        // akka.actor.deployment.<address>.create-as
        // --------------------------------
        val recipe: Option[ActorRecipe] = addressConfig.getSection("create-as") map { section ⇒
          val implementationClass = section.getString("class") match {
            case Some(impl) ⇒
              getClassFor[Actor](impl).fold(e ⇒ throw new ConfigurationException(
                "Config option [" + addressPath + ".create-as.class] load failed", e), identity)
            case None ⇒
              throw new ConfigurationException(
                "Config option [" + addressPath + ".create-as.class] is missing, need the fully qualified name of the class")
          }
          ActorRecipe(implementationClass)
        }

        // --------------------------------
        // akka.actor.deployment.<address>.remote
        // --------------------------------
        addressConfig.getSection("remote") match {
          case Some(remoteConfig) ⇒ // we have a 'remote' config section

            if (addressConfig.getSection("cluster").isDefined) throw new ConfigurationException(
              "Configuration for deployment ID [" + address + "] can not have both 'remote' and 'cluster' sections.")

            // --------------------------------
            // akka.actor.deployment.<address>.remote.nodes
            // --------------------------------
            val remoteAddresses = remoteConfig.getList("nodes") match {
              case Nil ⇒ Nil
              case nodes ⇒
                def raiseRemoteNodeParsingError() = throw new ConfigurationException(
                  "Config option [" + addressPath +
                    ".remote.nodes] needs to be a list with elements on format \"<hostname>:<port>\", was [" + nodes.mkString(", ") + "]")

                nodes map { node ⇒
                  val tokenizer = new java.util.StringTokenizer(node, ":")
                  val hostname = tokenizer.nextElement.toString
                  if ((hostname eq null) || (hostname == "")) raiseRemoteNodeParsingError()
                  val port = try tokenizer.nextElement.toString.toInt catch {
                    case e: Exception ⇒ raiseRemoteNodeParsingError()
                  }
                  if (port == 0) raiseRemoteNodeParsingError()
                  val inet = new InetSocketAddress(hostname, port) //FIXME switch to non-ip-tied
                  RemoteAddress(Option(inet.getAddress).map(_.getHostAddress).getOrElse(hostname), inet.getPort)
                }
            }

            Some(Deploy(address, recipe, router, nrOfInstances, failureDetector, RemoteScope(remoteAddresses)))

          case None ⇒ // check for 'cluster' config section

            // --------------------------------
            // akka.actor.deployment.<address>.cluster
            // --------------------------------
            addressConfig.getSection("cluster") match {
              case None ⇒ None
              case Some(clusterConfig) ⇒

                // --------------------------------
                // akka.actor.deployment.<address>.cluster.preferred-nodes
                // --------------------------------

                val preferredNodes = clusterConfig.getList("preferred-nodes") match {
                  case Nil ⇒ Nil
                  case homes ⇒
                    def raiseHomeConfigError() = throw new ConfigurationException(
                      "Config option [" + addressPath +
                        ".cluster.preferred-nodes] needs to be a list with elements on format\n'host:<hostname>', 'ip:<ip address>' or 'node:<node name>', was [" +
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
                // akka.actor.deployment.<address>.cluster.replication
                // --------------------------------
                clusterConfig.getSection("replication") match {
                  case None ⇒
                    Some(Deploy(address, recipe, router, nrOfInstances, failureDetector, deploymentConfig.ClusterScope(preferredNodes, Transient)))

                  case Some(replicationConfig) ⇒
                    val storage = replicationConfig.getString("storage", "transaction-log") match {
                      case "transaction-log" ⇒ TransactionLog
                      case "data-grid"       ⇒ DataGrid
                      case unknown ⇒
                        throw new ConfigurationException("Config option [" + addressPath +
                          ".cluster.replication.storage] needs to be either [\"transaction-log\"] or [\"data-grid\"] - was [" +
                          unknown + "]")
                    }
                    val strategy = replicationConfig.getString("strategy", "write-through") match {
                      case "write-through" ⇒ WriteThrough
                      case "write-behind"  ⇒ WriteBehind
                      case unknown ⇒
                        throw new ConfigurationException("Config option [" + addressPath +
                          ".cluster.replication.strategy] needs to be either [\"write-through\"] or [\"write-behind\"] - was [" +
                          unknown + "]")
                    }
                    Some(Deploy(address, recipe, router, nrOfInstances, failureDetector, deploymentConfig.ClusterScope(preferredNodes, Replication(storage, strategy))))
                }
            }
        }
    }
  }

  private[akka] def throwDeploymentBoundException(deployment: Deploy): Nothing = {
    val e = new DeploymentAlreadyBoundException("Address [" + deployment.address + "] already bound to [" + deployment + "]")
    app.eventHandler.error(e, this, e.getMessage)
    throw e
  }

  private[akka] def thrownNoDeploymentBoundException(address: String): Nothing = {
    val e = new NoDeploymentBoundException("Address [" + address + "] is not bound to a deployment")
    app.eventHandler.error(e, this, e.getMessage)
    throw e
  }
}

/**
 * Simple local deployer, only for internal use.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object LocalDeployer extends ActorDeployer {
  private val deployments = new ConcurrentHashMap[String, Deploy]

  private[akka] def init(deployments: Seq[Deploy]) {
    deployments foreach (deploy(_)) // deploy
  }

  private[akka] def shutdown() {
    deployments.clear() //TODO do something else/more?
  }

  private[akka] def deploy(deployment: Deploy) {
    deployments.putIfAbsent(deployment.address, deployment)
  }

  private[akka] def lookupDeploymentFor(address: String): Option[Deploy] = Option(deployments.get(address))
}

class DeploymentException private[akka] (message: String) extends AkkaException(message)
class DeploymentAlreadyBoundException private[akka] (message: String) extends AkkaException(message)
class NoDeploymentBoundException private[akka] (message: String) extends AkkaException(message)
