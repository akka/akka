/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import scala.util.Duration
import com.typesafe.config._
import akka.routing._
import java.util.concurrent.{ TimeUnit, ConcurrentHashMap }
import akka.util.ReflectiveAccess

case class Deploy(path: String, config: Config, routing: RouterConfig = NoRouter, scope: Scope = LocalScope)

case class ActorRecipe(implementationClass: Class[_ <: Actor]) //TODO Add ActorConfiguration here

trait Scope
case class LocalScope() extends Scope
case object LocalScope extends Scope

/**
 * Deployer maps actor paths to actor deployments.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class Deployer(val settings: ActorSystem.Settings, val classloader: ClassLoader) {

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

    val deployment = config.withFallback(default)

    val routees = deployment.getStringList("routees.paths").asScala.toSeq

    val nrOfInstances = deployment.getInt("nr-of-instances")

    val within = Duration(deployment.getMilliseconds("within"), TimeUnit.MILLISECONDS)

    val resizer: Option[Resizer] = if (config.hasPath("resizer")) {
      Some(DefaultResizer(deployment.getConfig("resizer")))
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
      case fqn ⇒
        val args = Seq(classOf[Config] -> deployment)
        ReflectiveAccess.createInstance[RouterConfig](fqn, args, classloader) match {
          case Right(router) ⇒ router
          case Left(exception) ⇒
            throw new IllegalArgumentException(
              ("Cannot instantiate router [%s], defined in [%s], " +
                "make sure it extends [akka.routing.RouterConfig] and has constructor with " +
                "[com.typesafe.config.Config] parameter")
                .format(fqn, key), exception)
        }
    }

    Some(Deploy(key, deployment, router, LocalScope))
  }

}
