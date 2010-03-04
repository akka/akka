
package se.scalablesolutions.akka.osgi.sample

import org.osgi.framework.{BundleContext, BundleActivator}
import se.scalablesolutions.akka.config.ScalaConfig._
import se.scalablesolutions.akka.actor.{Supervisor, SupervisorFactory}

class Activator extends BundleActivator {

  var supervisor: Supervisor = _

  val ping = new Ping

  def start(context: BundleContext) {
    println("Starting Akka OSGi sample")

    supervisor = SupervisorFactory(
      SupervisorConfig(
        RestartStrategy(OneForOne, 3, 100, List(classOf[Exception])),
        Supervise(ping, LifeCycle(Permanent)) :: Nil)).newInstance

    supervisor.start
    println("Supervisor: " + supervisor)

    println("Sending ping")
    ping send CounterMessage(0)

  }

  def stop(context: BundleContext) {
    println("Stopping Akka OSGi sample")
    
    supervisor.stop
  }

}