package akka.osgi.blueprint

import org.osgi.framework.BundleContext
import akka.osgi.OsgiActorSystemFactory
import collection.mutable.Buffer
import akka.actor.{ Actor, Props, ActorSystem }

/**
 * A set of helper/factory classes to build a Akka system using Blueprint
 */
class BlueprintActorSystemFactory(context: BundleContext, name: String) extends OsgiActorSystemFactory(context) {

  val systems: Buffer[ActorSystem] = Buffer()

  def this(context: BundleContext) = this(context, null)

  def create: ActorSystem = create(null)
  def create(name: String): ActorSystem = {
    val system = super.createActorSystem(name)
    systems += system
    system
  }

  def destroy = for (system ← systems) {
    system.shutdown()
  }
}

class BlueprintActorSystem(context: BundleContext, system: ActorSystem) {

  def createActor(name: String) = system.actorOf(Props(context.getBundle.loadClass(name).asInstanceOf[Class[Actor]]))

}

