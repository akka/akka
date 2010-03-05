package sample.camel

import org.apache.camel.builder.RouteBuilder
import org.apache.camel.{Exchange, Processor}

import se.scalablesolutions.akka.actor.SupervisorFactory
import se.scalablesolutions.akka.camel.CamelContextManager
import se.scalablesolutions.akka.config.ScalaConfig._

/**
 * @author Martin Krasser
 */
class Boot {

  CamelContextManager.init()
  CamelContextManager.context.addRoutes(new CustomRouteBuilder)

  val factory = SupervisorFactory(
    SupervisorConfig(
      RestartStrategy(OneForOne, 3, 100, List(classOf[Exception])),
      Supervise(new Consumer1, LifeCycle(Permanent)) ::
      Supervise(new Consumer2, LifeCycle(Permanent)) :: Nil))
  factory.newInstance.start

  val producer = new Producer1
  val mediator = new Transformer(producer)
  val consumer = new Consumer3(mediator)

  producer.start
  mediator.start
  consumer.start

}

class CustomRouteBuilder extends RouteBuilder {

  def configure {
    val actorUri = "actor:%s" format classOf[Consumer2].getName
    from("jetty:http://0.0.0.0:8877/camel/test2").to(actorUri)
    from("direct:welcome").process(new Processor() {
      def process(exchange: Exchange) {
        exchange.getOut.setBody("Welcome %s" format exchange.getIn.getBody)
      }
    })

  }

}