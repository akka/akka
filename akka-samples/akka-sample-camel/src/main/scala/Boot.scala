package sample.camel

import org.apache.camel.builder.RouteBuilder
import org.apache.camel.impl.DefaultCamelContext

import se.scalablesolutions.akka.actor.SupervisorFactory
import se.scalablesolutions.akka.camel.service.CamelContextManager
import se.scalablesolutions.akka.config.ScalaConfig._

/**
 * @author Martin Krasser
 */
class Boot {

  import CamelContextManager.context

  context = new DefaultCamelContext
  context.addRoutes(new CustomRouteBuilder)

  val factory = SupervisorFactory(
    SupervisorConfig(
      RestartStrategy(OneForOne, 3, 100, List(classOf[Exception])),
      Supervise(new Consumer1, LifeCycle(Permanent)) ::
      Supervise(new Consumer2, LifeCycle(Permanent)) :: Nil))
  factory.newInstance.start

}

class CustomRouteBuilder extends RouteBuilder {

  def configure {
    val actorUri = "actor:%s" format classOf[Consumer2].getName
    from ("jetty:http://0.0.0.0:8877/camel/test2").to(actorUri)
  }

}