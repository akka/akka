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
import akka.event.EventStream
import com.typesafe.config._

/**
 * Deployer maps actor paths to actor deployments.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class Deployer(val settings: ActorSystem.Settings) {

  import scala.collection.JavaConverters._

  private val deployments = new ConcurrentHashMap[String, Deploy]
  private val config = settings.config.getConfig("akka.actor.deployment")
  protected val default = config.getConfig("default")
  config.root.asScala flatMap {
    case ("default", _)             ⇒ None
    case (key, value: ConfigObject) ⇒ parseConfig(key, value.toConfig)
    case _                          ⇒ None
  } foreach deploy

  def lookup(path: String): Option[Deploy] = Option(deployments.get(path))

  def deploy(d: Deploy): Unit = deployments.put(d.path, d)

  protected def parseConfig(key: String, config: Config): Option[Deploy] = {
    import akka.util.ReflectiveAccess.getClassFor

    val deployment = config.withFallback(default)
    // --------------------------------
    // akka.actor.deployment.<path>.router
    // --------------------------------
    val router: Routing = deployment.getString("router") match {
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
          "Config option [akka.actor.deployment." + key +
            ".nr-of-instances] needs to be either [\"auto\"] or [1-N] - was [" +
            wasValue + "]")

        deployment.getAnyRef("nr-of-instances").asInstanceOf[Any] match {
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
      deployment.getString("create-as.class") match {
        case "" ⇒ None
        case impl ⇒
          val implementationClass = getClassFor[Actor](impl).fold(e ⇒ throw new ConfigurationException(
            "Config option [akka.actor.deployment." + key + ".create-as.class] load failed", e), identity)
          Some(ActorRecipe(implementationClass))
      }

    Some(Deploy(key, recipe, router, nrOfInstances, LocalScope))
  }

}
