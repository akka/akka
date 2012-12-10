package akka.osgi.test

import akka.osgi.ActorSystemActivator
import akka.actor.ActorSystem
import org.osgi.framework.BundleContext

class TestActivator extends ActorSystemActivator {

  def configure(context: BundleContext, system: ActorSystem) {
    registerService(context, system)
  }
}