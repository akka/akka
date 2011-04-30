/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import collection.immutable.Seq

import java.util.concurrent.ConcurrentHashMap

import akka.event.EventHandler
import akka.actor.DeploymentConfig._
import akka.config.{ConfigurationException, Config}
import akka.util.ReflectiveAccess
import akka.AkkaException

/**
 * Programatic deployment configuration classes. Most values have defaults and can be left out.
 * <p/>
 * Example Scala API:
 * <pre>
 *   import akka.actor.DeploymentConfig._
 *
 *   val deploymentHello = Deploy("service:hello", Local)
 *
 *   val deploymentE     = Deploy("service:e", AutoReplicate, Clustered(Home("darkstar.lan", 7887), Stateful))
 *
 *   val deploymentPi1   = Deploy("service:pi", Replicate(3), Clustered(Home("darkstar.lan", 7887), Stateless(RoundRobin)))
 *
 *   // same thing as 'deploymentPi1' but more explicit
 *   val deploymentPi2   =
 *     Deploy(
 *       address = "service:pi",
 *         replicas = 3,
 *         scope = Clustered(
 *           home = Home("darkstar.lan", 7887)
 *           state = Stateless(
 *             routing = RoundRobin
 *           )
 *         )
 *       )
 * </pre>
 * Example Java API:
 * <pre>
 *   import static akka.actor.*;
 *
 *   val deploymentHello = new Deploy("service:hello", new Local());
 *
 *   val deploymentE     = new Deploy("service:e", new AutoReplicate(), new Clustered(new Home("darkstar.lan", 7887), new Stateful()));
 *
 *   val deploymentPi1   = new Deploy("service:pi", new Replicate(3), new Clustered(new Home("darkstar.lan", 7887), new Stateless(new RoundRobin())))
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object DeploymentConfig {

  // --------------------------------
  // --- Deploy
  // --------------------------------
  case class Deploy(address: String, routing: Routing = Direct, scope: Scope = Local)

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
    home: Home = Home("localhost", 2552),
    replication: Replication = NoReplicas,
    state: State = Stateful) extends Scope

  // For Java API
  case class Local() extends Scope

  // For Scala API
  case object Local extends Scope

  // --------------------------------
  // --- Home
  // --------------------------------
  case class Home(hostname: String, port: Int)

  // --------------------------------
  // --- Replication
  // --------------------------------
  sealed trait Replication
  class ReplicationBase(factor: Int) extends Replication {
    if (factor < 1) throw new IllegalArgumentException("Replication factor can not be negative or zero")
  }
  case class Replicate(factor: Int) extends ReplicationBase(factor)

  // For Java API
  case class AutoReplicate() extends Replication
  case class NoReplicas() extends ReplicationBase(1)

  // For Scala API
  case object AutoReplicate extends Replication
  case object NoReplicas extends ReplicationBase(1)

  // --------------------------------
  // --- State
  // --------------------------------
  sealed trait State

  // For Java API
  case class Stateless() extends State
  case class Stateful() extends State

  // For Scala API
  case object Stateless extends State
  case object Stateful extends State
}

/**
 * Deployer maps actor deployments to actor addresses.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Deployer {
  lazy val useClusterDeployer = ReflectiveAccess.ClusterModule.isEnabled
  lazy val cluster            = ReflectiveAccess.ClusterModule.clusterDeployer
  lazy val local              = new LocalDeployer

  def deploy(deployment: Deploy) {
    if (deployment eq null) throw new IllegalArgumentException("Deploy can not be null")
    val address = deployment.address
    Address.validate(address)
    if (useClusterDeployer) cluster.deploy(deployment)
    else                    local.deploy(deployment)
  }

  def deploy(deployment: Seq[Deploy]) {
    deployment foreach (deploy(_))
  }

  private def deployLocally(deployment: Deploy) {
    deployment match {
      case Deploy(address, Direct, Clustered(Home(hostname, port), _, _)) =>
        val currentRemoteServerAddress = Actor.remote.address
        if (currentRemoteServerAddress.getHostName == hostname) { // are we on the right server?
          if (currentRemoteServerAddress.getPort != port) throw new ConfigurationException(
            "Remote server started on [" + hostname +
            "] is started on port [" + currentRemoteServerAddress.getPort +
            "] can not use deployment configuration [" + deployment +
            "] due to invalid port [" + port + "]")

          // FIXME how to handle registerPerSession
//          Actor.remote.register(Actor.newLocalActorRef(address))
        }

      case Deploy(_, routing, Clustered(Home(hostname, port), replicas, state)) =>
        // FIXME clustered actor deployment

      case _ => // local deployment do nothing
    }
  }

  /**
   * Undeploy is idemponent. E.g. safe to invoke multiple times.
   */
  def undeploy(deployment: Deploy) {
    if (useClusterDeployer) cluster.undeploy(deployment)
    else                    local.undeploy(deployment)
  }

  def undeployAll() {
    if (useClusterDeployer) cluster.undeployAll()
    else                    local.undeployAll()
  }

  /**
   * Same as 'lookupDeploymentFor' but throws an exception if no deployment is bound.
   */
  def deploymentFor(address: String): Deploy = {
    lookupDeploymentFor(address) match {
      case Some(deployment) => deployment
      case None             => thrownNoDeploymentBoundException(address)
    }
  }

  def lookupDeploymentFor(address: String): Option[Deploy] = {
    val deployment_? =
      if (useClusterDeployer) cluster.lookupDeploymentFor(address)
      else                    local.lookupDeploymentFor(address)
    if (deployment_?.isDefined && (deployment_?.get ne null)) deployment_?
    else {
      val newDeployment =
        try {
          lookupInConfig(address)
        } catch {
          case e: ConfigurationException =>
            EventHandler.error(e, this, e.getMessage)
            throw e
        }
      newDeployment foreach { d =>
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

  /**
   * Lookup deployment in 'akka.conf' configuration file.
   */
  def lookupInConfig(address: String): Option[Deploy] = {

    // --------------------------------
    // akka.actor.deployment.<address>
    // --------------------------------
    val addressPath = "akka.actor.deployment." + address
    Config.config.getSection(addressPath) match {
      case None                => Some(Deploy(address, Direct, Local))
      case Some(addressConfig) =>

        // --------------------------------
        // akka.actor.deployment.<address>.router
        // --------------------------------
        val router = addressConfig.getString("router", "direct") match {
          case "direct"              => Direct
          case "round-robin"         => RoundRobin
          case "random"              => Random
          case "least-cpu"           => LeastCPU
          case "least-ram"           => LeastRAM
          case "least-messages"      => LeastMessages
          case customRouterClassName =>
            val customRouter = try {
              Class.forName(customRouterClassName).newInstance.asInstanceOf[AnyRef]
            } catch {
              case e => throw new ConfigurationException(
              "Config option [" + addressPath + ".router] needs to be one of " +
                  "[\"direct\", \"round-robin\", \"random\", \"least-cpu\", \"least-ram\", \"least-messages\" or FQN of router class]")
            }
            CustomRouter(customRouter)
        }

        // --------------------------------
        // akka.actor.deployment.<address>.clustered
        // --------------------------------
        addressConfig.getSection("clustered") match {
          case None =>
            Some(Deploy(address, router, Local)) // deploy locally

          case Some(clusteredConfig) =>

            // --------------------------------
            // akka.actor.deployment.<address>.clustered.home
            // --------------------------------
            val home = clusteredConfig.getListAny("home") match {
              case List(hostname: String, port: String) =>
                try {
                  Home(hostname, port.toInt)
                } catch {
                  case e: NumberFormatException =>
                    throw new ConfigurationException(
                      "Config option [" + addressPath +
                      ".clustered.home] needs to be an array on format [[\"hostname\", port]] - was [[" +
                      hostname + ", " + port + "]]")
                }
              case invalid => throw new ConfigurationException(
                "Config option [" + addressPath +
                ".clustered.home] needs to be an arrayon format [\"hostname\", port] - was [" +
                invalid + "]")
            }

            // --------------------------------
            // akka.actor.deployment.<address>.clustered.replicas
            // --------------------------------
            val replicas = clusteredConfig.getAny("replicas", 1) match {
              case "auto"               => AutoReplicate
              case "1"                  => NoReplicas
              case nrOfReplicas: String =>
                try {
                  Replicate(nrOfReplicas.toInt)
                } catch {
                  case e: NumberFormatException =>
                    throw new ConfigurationException(
                      "Config option [" + addressPath +
                      ".clustered.replicas] needs to be either [\"auto\"] or [1-N] - was [" +
                      nrOfReplicas + "]")
                }
            }

            // --------------------------------
            // akka.actor.deployment.<address>.clustered.stateless
            // --------------------------------
            val state =
              if (clusteredConfig.getBool("stateless", false)) Stateless
              else Stateful

            Some(Deploy(address, router, Clustered(home, replicas, state)))
        }
    }
  }

  def isLocal(deployment: Deploy): Boolean = deployment match {
    case Deploy(_, _, Local) => true
    case _                   => false
  }

  def isClustered(deployment: Deploy): Boolean = isLocal(deployment)

  def isLocal(address: String): Boolean = isLocal(deploymentFor(address))

  def isClustered(address: String): Boolean = !isLocal(address)

  private def throwDeploymentBoundException(deployment: Deploy): Nothing = {
    val e = new DeploymentAlreadyBoundException(
      "Address [" + deployment.address +
      "] already bound to [" + deployment +
      "]. You have to invoke 'undeploy(deployment) first.")
    EventHandler.error(e, this, e.getMessage)
    throw e
  }

  private def thrownNoDeploymentBoundException(address: String): Nothing = {
    val e = new NoDeploymentBoundException("Address [" + address + "] is not bound to a deployment")
    EventHandler.error(e, this, e.getMessage)
    throw e
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class LocalDeployer {
  private val deployments = new ConcurrentHashMap[String, Deploy]

  def deploy(deployment: Deploy) {
    if (deployments.putIfAbsent(deployment.address, deployment) != deployment) {
      println("----- DEPLOYING " + deployment)
      // FIXME do automatic 'undeploy' and redeploy (perhaps have it configurable if redeploy should be done or exception thrown)
      // throwDeploymentBoundException(deployment)
    }
  }

  def undeploy(deployment: Deploy) {
    deployments.remove(deployment.address)
  }

  def undeployAll() {
    deployments.clear()
  }

  def lookupDeploymentFor(address: String): Option[Deploy] = {
    val deployment = deployments.get(address)
    if (deployment eq null) None
    else Some(deployment)
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Address {
  private val validAddressPattern = java.util.regex.Pattern.compile("[0-9a-zA-Z\\-\\_\\$\\.]+")

  def validate(address: String) {
    if (validAddressPattern.matcher(address).matches) true
    else {
      val e = new IllegalArgumentException("Address [" + address + "] is not valid, need to follow pattern [0-9a-zA-Z\\-\\_\\$]+")
      EventHandler.error(e, this, e.getMessage)
      throw e
    }
  }
}

class DeploymentException private[akka](message: String) extends AkkaException(message)
class DeploymentAlreadyBoundException private[akka](message: String) extends AkkaException(message)
class NoDeploymentBoundException private[akka](message: String) extends AkkaException(message)
