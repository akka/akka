/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import collection.immutable.Seq
import java.util.concurrent.ConcurrentHashMap
import akka.event.Logging
import akka.actor.DeploymentConfig._
import akka.AkkaException
import akka.config.{ Configuration, ConfigurationException }
import akka.util.Duration
import java.net.InetSocketAddress
import akka.remote.RemoteAddress
import akka.event.EventStream

trait ActorDeployer {
  private[akka] def init(deployments: Seq[Deploy]): Unit
  private[akka] def shutdown(): Unit //TODO Why should we have "shutdown", should be crash only?
  private[akka] def deploy(deployment: Deploy): Unit
  private[akka] def lookupDeploymentFor(path: String): Option[Deploy]
  def lookupDeployment(path: String): Option[Deploy] = path match {
    case null | ""              ⇒ None
    case s if s.startsWith("$") ⇒ None
    case some                   ⇒ lookupDeploymentFor(some)
  }
  private[akka] def deploy(deployment: Seq[Deploy]): Unit = deployment foreach (deploy(_))
}

/**
 * Deployer maps actor paths to actor deployments.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class Deployer(val AkkaConfig: ActorSystem.AkkaConfig, val eventStream: EventStream, val nodename: String) extends ActorDeployer {

  val deploymentConfig = new DeploymentConfig(nodename)
  val log = Logging(eventStream, this)

  val instance: ActorDeployer = {
    val deployer = new LocalDeployer()
    deployer.init(deploymentsInConfig)
    deployer
  }

  def start(): Unit = instance.toString //Force evaluation

  private[akka] def init(deployments: Seq[Deploy]) = instance.init(deployments)

  def shutdown(): Unit = instance.shutdown() //TODO FIXME Why should we have "shutdown", should be crash only?

  def deploy(deployment: Deploy): Unit = instance.deploy(deployment)

  def isLocal(deployment: Deploy): Boolean = deployment match {
    case Deploy(_, _, _, _, LocalScope) | Deploy(_, _, _, _, _: LocalScope) ⇒ true
    case _ ⇒ false
  }

  def isClustered(deployment: Deploy): Boolean = !isLocal(deployment)

  def isLocal(path: String): Boolean = isLocal(deploymentFor(path)) //TODO Should this throw exception if path not found?

  def isClustered(path: String): Boolean = !isLocal(path) //TODO Should this throw exception if path not found?

  /**
   * Same as 'lookupDeploymentFor' but throws an exception if no deployment is bound.
   */
  private[akka] def deploymentFor(path: String): Deploy = {
    lookupDeploymentFor(path) match {
      case Some(deployment) ⇒ deployment
      case None             ⇒ thrownNoDeploymentBoundException(path)
    }
  }

  private[akka] def lookupDeploymentFor(path: String): Option[Deploy] =
    instance.lookupDeploymentFor(path)

  private[akka] def deploymentsInConfig: List[Deploy] = {
    for {
      path ← pathsInConfig
      deployment ← lookupInConfig(path)
    } yield deployment
  }

  private[akka] def pathsInConfig: List[String] = {
    val deploymentPath = "akka.actor.deployment"
    AkkaConfig.config.getSection(deploymentPath) match {
      case None ⇒ Nil
      case Some(pathConfig) ⇒
        pathConfig.map.keySet
          .map(path ⇒ path.substring(0, path.indexOf(".")))
          .toSet.toList // toSet to force uniqueness
    }
  }

  /**
   * Lookup deployment in 'akka.conf' configuration file.
   */
  private[akka] def lookupInConfig(path: String, configuration: Configuration = AkkaConfig.config): Option[Deploy] = {
    import akka.util.ReflectiveAccess.{ createInstance, emptyArguments, emptyParams, getClassFor }

    // --------------------------------
    // akka.actor.deployment.<path>
    // --------------------------------
    val deploymentKey = "akka.actor.deployment." + path
    configuration.getSection(deploymentKey) match {
      case None ⇒ None
      case Some(pathConfig) ⇒

        // --------------------------------
        // akka.actor.deployment.<path>.router
        // --------------------------------
        val router: Routing = pathConfig.getString("router", "direct") match {
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
        // akka.actor.deployment.<path>.nr-of-instances
        // --------------------------------
        val nrOfInstances = {
          if (router == Direct) OneNrOfInstances
          else {
            pathConfig.getAny("nr-of-instances", "1") match {
              case "auto" ⇒ AutoNrOfInstances
              case "1"    ⇒ OneNrOfInstances
              case "0"    ⇒ ZeroNrOfInstances
              case nrOfReplicas: String ⇒
                try {
                  new NrOfInstances(nrOfReplicas.toInt)
                } catch {
                  case e: Exception ⇒
                    throw new ConfigurationException(
                      "Config option [" + deploymentKey +
                        ".nr-of-instances] needs to be either [\"auto\"] or [1-N] - was [" +
                        nrOfReplicas + "]")
                }
            }
          }
        }

        // --------------------------------
        // akka.actor.deployment.<path>.create-as
        // --------------------------------
        val recipe: Option[ActorRecipe] = pathConfig.getSection("create-as") map { section ⇒
          val implementationClass = section.getString("class") match {
            case Some(impl) ⇒
              getClassFor[Actor](impl).fold(e ⇒ throw new ConfigurationException(
                "Config option [" + deploymentKey + ".create-as.class] load failed", e), identity)
            case None ⇒
              throw new ConfigurationException(
                "Config option [" + deploymentKey + ".create-as.class] is missing, need the fully qualified name of the class")
          }
          ActorRecipe(implementationClass)
        }

        // --------------------------------
        // akka.actor.deployment.<path>.remote
        // --------------------------------
        pathConfig.getSection("remote") match {
          case Some(remoteConfig) ⇒ // we have a 'remote' config section

            if (pathConfig.getSection("cluster").isDefined) throw new ConfigurationException(
              "Configuration for deployment ID [" + path + "] can not have both 'remote' and 'cluster' sections.")

            // --------------------------------
            // akka.actor.deployment.<path>.remote.nodes
            // --------------------------------
            val remoteAddresses = remoteConfig.getList("nodes") match {
              case Nil ⇒ Nil
              case nodes ⇒
                def raiseRemoteNodeParsingError() = throw new ConfigurationException(
                  "Config option [" + deploymentKey +
                    ".remote.nodes] needs to be a list with elements on format \"<hostname>:<port>\", was [" + nodes.mkString(", ") + "]")

                nodes map { node ⇒
                  val tokenizer = new java.util.StringTokenizer(node, ":")
                  val hostname = tokenizer.nextElement.toString
                  if ((hostname eq null) || (hostname == "")) raiseRemoteNodeParsingError()
                  val port = try tokenizer.nextElement.toString.toInt catch {
                    case e: Exception ⇒ raiseRemoteNodeParsingError()
                  }
                  if (port == 0) raiseRemoteNodeParsingError()

                  RemoteAddress(new InetSocketAddress(hostname, port))
                }
            }

            Some(Deploy(path, recipe, router, nrOfInstances, RemoteScope(remoteAddresses)))

          case None ⇒ // check for 'cluster' config section

            // --------------------------------
            // akka.actor.deployment.<path>.cluster
            // --------------------------------
            pathConfig.getSection("cluster") match {
              case None ⇒ None
              case Some(clusterConfig) ⇒

                // --------------------------------
                // akka.actor.deployment.<path>.cluster.preferred-nodes
                // --------------------------------

                val preferredNodes = clusterConfig.getList("preferred-nodes") match {
                  case Nil ⇒ Nil
                  case homes ⇒
                    def raiseHomeConfigError() = throw new ConfigurationException(
                      "Config option [" + deploymentKey +
                        ".cluster.preferred-nodes] needs to be a list with elements on format\n'host:<hostname>', 'ip:<ip address>' or 'node:<node name>', was [" +
                        homes + "]")

                    homes map { home ⇒
                      if (!(home.startsWith("host:") || home.startsWith("node:") || home.startsWith("ip:"))) raiseHomeConfigError()

                      val tokenizer = new java.util.StringTokenizer(home, ":")
                      val protocol = tokenizer.nextElement
                      val address = tokenizer.nextElement.asInstanceOf[String]

                      protocol match {
                        case "node" ⇒ Node(address)
                        case _      ⇒ raiseHomeConfigError()
                      }
                    }
                }

                // --------------------------------
                // akka.actor.deployment.<path>.cluster.replication
                // --------------------------------
                clusterConfig.getSection("replication") match {
                  case None ⇒
                    Some(Deploy(path, recipe, router, nrOfInstances, deploymentConfig.ClusterScope(preferredNodes, Transient)))

                  case Some(replicationConfig) ⇒
                    val storage = replicationConfig.getString("storage", "transaction-log") match {
                      case "transaction-log" ⇒ TransactionLog
                      case "data-grid"       ⇒ DataGrid
                      case unknown ⇒
                        throw new ConfigurationException("Config option [" + deploymentKey +
                          ".cluster.replication.storage] needs to be either [\"transaction-log\"] or [\"data-grid\"] - was [" +
                          unknown + "]")
                    }
                    val strategy = replicationConfig.getString("strategy", "write-through") match {
                      case "write-through" ⇒ WriteThrough
                      case "write-behind"  ⇒ WriteBehind
                      case unknown ⇒
                        throw new ConfigurationException("Config option [" + deploymentKey +
                          ".cluster.replication.strategy] needs to be either [\"write-through\"] or [\"write-behind\"] - was [" +
                          unknown + "]")
                    }
                    Some(Deploy(path, recipe, router, nrOfInstances, deploymentConfig.ClusterScope(preferredNodes, Replication(storage, strategy))))
                }
            }
        }
    }
  }

  private[akka] def throwDeploymentBoundException(deployment: Deploy): Nothing = {
    val e = new DeploymentAlreadyBoundException("Path [" + deployment.path + "] already bound to [" + deployment + "]")
    log.error(e, e.getMessage)
    throw e
  }

  private[akka] def thrownNoDeploymentBoundException(path: String): Nothing = {
    val e = new NoDeploymentBoundException("Path [" + path + "] is not bound to a deployment")
    log.error(e, e.getMessage)
    throw e
  }
}

/**
 * Simple local deployer, only for internal use.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class LocalDeployer extends ActorDeployer {
  private val deployments = new ConcurrentHashMap[String, Deploy]

  private[akka] def init(deployments: Seq[Deploy]): Unit = deployments foreach deploy // deploy

  private[akka] def shutdown(): Unit = deployments.clear() //TODO do something else/more?

  private[akka] def deploy(deployment: Deploy): Unit = deployments.putIfAbsent(deployment.path, deployment)

  private[akka] def lookupDeploymentFor(path: String): Option[Deploy] = Option(deployments.get(path))
}

class DeploymentException private[akka] (message: String) extends AkkaException(message)
class DeploymentAlreadyBoundException private[akka] (message: String) extends AkkaException(message)
class NoDeploymentBoundException private[akka] (message: String) extends AkkaException(message)
