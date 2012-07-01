import akka.actor.{ Props, ActorSystem }
import akka.osgi.ActorSystemActivator
import org.apache.servicemix.examples.akka.Listener
import org.apache.servicemix.examples.akka.Master

//#Activator
class Activator extends ActorSystemActivator("PiSystem") {

  def configure(context: BundleContext, system: ActorSystem) {
    // optionally register the ActorSystem in the OSGi Service Registry
    registerService(context, system)

    val listener = system.actorOf(Props[Listener], name = "listener")
    val master = system.actorOf(Props(new Master(4, 10000, 10000, listener)), name = "master")
    master ! Calculate
  }

}
//#Activator