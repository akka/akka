/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import collection.immutable.Seq
import akka.event.Logging
import akka.AkkaException
import akka.config.ConfigurationException
import akka.util.Duration
import akka.event.EventStream
import com.typesafe.config._
import akka.routing._
import java.util.concurrent.{ TimeUnit, ConcurrentHashMap }

case class Deploy(path: String, config: Config, recipe: Option[ActorRecipe] = None, routing: RouterConfig = NoRouter, scope: Scope = LocalScope)

case class ActorRecipe(implementationClass: Class[_ <: Actor]) //TODO Add ActorConfiguration here

trait Scope
case class LocalScope() extends Scope
case object LocalScope extends Scope

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

    val routees = deployment.getStringList("routees.paths").asScala.toSeq

    val nrOfInstances = deployment.getInt("nr-of-instances")

    val within = Duration(deployment.getMilliseconds("within"), TimeUnit.MILLISECONDS)

    val resizer: Option[Resizer] = if (config.hasPath("resizer")) {
      val resizerConfig = deployment.getConfig("resizer")
      Some(DefaultResizer(
        lowerBound = resizerConfig.getInt("lower-bound"),
        upperBound = resizerConfig.getInt("upper-bound"),
        pressureThreshold = resizerConfig.getInt("pressure-threshold"),
        rampupRate = resizerConfig.getDouble("rampup-rate"),
        backoffThreshold = resizerConfig.getDouble("backoff-threshold"),
        backoffRate = resizerConfig.getDouble("backoff-rate"),
        stopDelay = Duration(resizerConfig.getMilliseconds("stop-delay"), TimeUnit.MILLISECONDS),
        messagesPerResize = resizerConfig.getInt("messages-per-resize")))
    } else {
      None
    }

    val router: RouterConfig = deployment.getString("router") match {
      case "from-code"        ⇒ NoRouter
      case "round-robin"      ⇒ RoundRobinRouter(nrOfInstances, routees, resizer)
      case "random"           ⇒ RandomRouter(nrOfInstances, routees, resizer)
      case "smallest-mailbox" ⇒ SmallestMailboxRouter(nrOfInstances, routees, resizer)
      case "scatter-gather"   ⇒ ScatterGatherFirstCompletedRouter(nrOfInstances, routees, within, resizer)
      case "broadcast"        ⇒ BroadcastRouter(nrOfInstances, routees, resizer)
      case x                  ⇒ throw new ConfigurationException("unknown router type " + x + " for path " + key)
    }

    val recipe: Option[ActorRecipe] =
      deployment.getString("create-as.class") match {
        case "" ⇒ None
        case impl ⇒
          val implementationClass = getClassFor[Actor](impl).fold(e ⇒ throw new ConfigurationException(
            "Config option [akka.actor.deployment." + key + ".create-as.class] load failed", e), identity)
          Some(ActorRecipe(implementationClass))
      }

    Some(Deploy(key, deployment, recipe, router, LocalScope))
  }

}
