/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import collection.immutable.Seq
import java.util.concurrent.ConcurrentHashMap
import akka.event.Logging
import akka.actor.DeploymentConfig._
import akka.AkkaException
import akka.config.ConfigurationException
import akka.util.Duration
import java.net.InetSocketAddress
import akka.remote.RemoteAddress
import akka.event.EventStream
import com.typesafe.config.Config

trait ActorDeployer {
  private[akka] def init(deployments: Seq[Deploy]): Unit
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
class Deployer(val settings: ActorSystem.Settings, val eventStream: EventStream, val nodename: String) extends ActorDeployer {

  val deploymentConfig = new DeploymentConfig(nodename)
  val log = Logging(eventStream, "Deployer")

  val instance: ActorDeployer = {
    val deployer = new LocalDeployer()
    deployer.init(deploymentsInConfig)
    deployer
  }

  def start(): Unit = instance.toString //Force evaluation

  private[akka] def init(deployments: Seq[Deploy]) = instance.init(deployments)

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
    for (path ← pathsInConfig) yield lookupInConfig(path)
  }

  private[akka] def pathsInConfig: List[String] = {
    def pathSubstring(path: String) = {
      val i = path.indexOf(".")
      if (i == -1) path else path.substring(0, i)
    }

    import scala.collection.JavaConverters._
    settings.config.getConfig("akka.actor.deployment").root.keySet.asScala
      .filterNot("default" ==)
      .map(path ⇒ pathSubstring(path))
      .toSet.toList // toSet to force uniqueness
  }

  /**
   * Lookup deployment in 'akka.conf' configuration file.
   */
  private[akka] def lookupInConfig(path: String, configuration: Config = settings.config): Deploy = {
    import scala.collection.JavaConverters._
    import akka.util.ReflectiveAccess.getClassFor

    val defaultDeploymentConfig = configuration.getConfig("akka.actor.deployment.default")

    // --------------------------------
    // akka.actor.deployment.<path>
    // --------------------------------
    val deploymentKey = "akka.actor.deployment." + path
    val deployment = configuration.getConfig(deploymentKey)

    val deploymentWithFallback = deployment.withFallback(defaultDeploymentConfig)
    // --------------------------------
    // akka.actor.deployment.<path>.router
    // --------------------------------
    val router: Routing = deploymentWithFallback.getString("router") match {
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
      if (router == NoRouting) OneNrOfInstances
      else {
        def invalidNrOfInstances(wasValue: Any) = new ConfigurationException(
          "Config option [" + deploymentKey +
            ".nr-of-instances] needs to be either [\"auto\"] or [1-N] - was [" +
            wasValue + "]")

        deploymentWithFallback.getAnyRef("nr-of-instances").asInstanceOf[Any] match {
          case "auto" ⇒ AutoNrOfInstances
          case 1      ⇒ OneNrOfInstances
          case 0      ⇒ ZeroNrOfInstances
          case nrOfReplicas: Number ⇒
            try {
              new NrOfInstances(nrOfReplicas.intValue)
            } catch {
              case e: Exception ⇒ throw invalidNrOfInstances(nrOfReplicas)
            }
          case unknown ⇒ throw invalidNrOfInstances(unknown)
        }
      }
    }

    // --------------------------------
    // akka.actor.deployment.<path>.create-as
    // --------------------------------
    val recipe: Option[ActorRecipe] =
      deploymentWithFallback.getString("create-as.class") match {
        case "" ⇒ None
        case impl ⇒
          val implementationClass = getClassFor[Actor](impl).fold(e ⇒ throw new ConfigurationException(
            "Config option [" + deploymentKey + ".create-as.class] load failed", e), identity)
          Some(ActorRecipe(implementationClass))
      }

    val remoteNodes = deploymentWithFallback.getStringList("remote.nodes").asScala.toSeq
    val clusterPreferredNodes = deploymentWithFallback.getStringList("cluster.preferred-nodes").asScala.toSeq

    // --------------------------------
    // akka.actor.deployment.<path>.remote
    // --------------------------------
    def parseRemote: Scope = {
      def raiseRemoteNodeParsingError() = throw new ConfigurationException(
        "Config option [" + deploymentKey +
          ".remote.nodes] needs to be a list with elements on format \"<hostname>:<port>\", was [" + remoteNodes.mkString(", ") + "]")

      val remoteAddresses = remoteNodes map { node ⇒
        val tokenizer = new java.util.StringTokenizer(node, ":")
        val hostname = tokenizer.nextElement.toString
        if ((hostname eq null) || (hostname == "")) raiseRemoteNodeParsingError()
        val port = try tokenizer.nextElement.toString.toInt catch {
          case e: Exception ⇒ raiseRemoteNodeParsingError()
        }
        if (port == 0) raiseRemoteNodeParsingError()

        RemoteAddress(settings.name, hostname, port)
      }

      RemoteScope(remoteAddresses)
    }

    // --------------------------------
    // akka.actor.deployment.<path>.cluster
    // --------------------------------
    def parseCluster: Scope = {
      def raiseHomeConfigError() = throw new ConfigurationException(
        "Config option [" + deploymentKey +
          ".cluster.preferred-nodes] needs to be a list with elements on format\n'host:<hostname>', 'ip:<ip address>' or 'node:<node name>', was [" +
          clusterPreferredNodes + "]")

      val remoteNodes = clusterPreferredNodes map { home ⇒
        if (!(home.startsWith("host:") || home.startsWith("node:") || home.startsWith("ip:"))) raiseHomeConfigError()

        val tokenizer = new java.util.StringTokenizer(home, ":")
        val protocol = tokenizer.nextElement
        val address = tokenizer.nextElement.asInstanceOf[String]

        // TODO host and ip protocols?
        protocol match {
          case "node" ⇒ Node(address)
          case _      ⇒ raiseHomeConfigError()
        }
      }
      deploymentConfig.ClusterScope(remoteNodes, parseClusterReplication)
    }

    // --------------------------------
    // akka.actor.deployment.<path>.cluster.replication
    // --------------------------------
    def parseClusterReplication: ReplicationScheme = {
      deployment.hasPath("cluster.replication") match {
        case false ⇒ Transient
        case true ⇒
          val replicationConfigWithFallback = deploymentWithFallback.getConfig("cluster.replication")
          val storage = replicationConfigWithFallback.getString("storage") match {
            case "transaction-log" ⇒ TransactionLog
            case "data-grid"       ⇒ DataGrid
            case unknown ⇒
              throw new ConfigurationException("Config option [" + deploymentKey +
                ".cluster.replication.storage] needs to be either [\"transaction-log\"] or [\"data-grid\"] - was [" +
                unknown + "]")
          }
          val strategy = replicationConfigWithFallback.getString("strategy") match {
            case "write-through" ⇒ WriteThrough
            case "write-behind"  ⇒ WriteBehind
            case unknown ⇒
              throw new ConfigurationException("Config option [" + deploymentKey +
                ".cluster.replication.strategy] needs to be either [\"write-through\"] or [\"write-behind\"] - was [" +
                unknown + "]")
          }
          Replication(storage, strategy)
      }
    }

    val scope = (remoteNodes, clusterPreferredNodes) match {
      case (Nil, Nil) ⇒
        LocalScope
      case (_, Nil) ⇒
        // we have a 'remote' config section
        parseRemote
      case (Nil, _) ⇒
        // we have a 'cluster' config section
        parseCluster
      case (_, _) ⇒ throw new ConfigurationException(
        "Configuration for deployment ID [" + path + "] can not have both 'remote' and 'cluster' sections.")
    }

    Deploy(path, recipe, router, nrOfInstances, scope)
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
