/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.management

import se.scalablesolutions.akka.kernel.actor.{SupervisorFactory, Actor}
import se.scalablesolutions.akka.kernel.config.ScalaConfig._
import se.scalablesolutions.akka.kernel.util.Logging

import javax.ws.rs.core.MultivaluedMap
import javax.ws.rs.{GET, POST, Path, QueryParam, Produces, WebApplicationException, Consumes}
import javax.management._
import javax.management.remote.{JMXConnector, JMXConnectorFactory, JMXServiceURL}
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}
import java.util.concurrent.ConcurrentHashMap

/**
 * REST interface to Akka's JMX service.
 * <p/>
 * Here is an example that retreives the current number of Actors. 
 * <pre>
 * http://localhost:9998/jmx
 *   ?service=service:jmx:rmi:///jndi/rmi://localhost:1099/jmxrmi
 *   &component=se.scalablesolutions.akka:type=Stats
 *   &attribute=counter_NrOfActors
 * </pre>
 */
@Path("/jmx")
class RestfulJMX extends Actor with Logging {
  private case class Request(service: String, component: String, attribute: String)

  private val connectors = new ConcurrentHashMap[String, JMXConnector]

  @GET
  @Produces(Array("text/plain"))
  def queryJMX(
    @QueryParam("service") service: String, 
    @QueryParam("component") component: String, 
    @QueryParam("attribute") attribute: String): String= 
    (this !! Request(service, component, attribute)).getOrElse("Error in REST JMX management service")

  override def receive: PartialFunction[Any, Unit] = {
    case Request(service, component, attribute) => reply(retrieveAttribute(service, component, attribute))
  }

  private def retrieveAttribute(service: String, component: String, attribute: String): String = {
    try {
      var connector = connectors.putIfAbsent(service, JMXConnectorFactory.connect(new JMXServiceURL(service)))
      connector.getMBeanServerConnection.getAttribute(new ObjectName(component), attribute).toString
    } catch {
      case e: Exception =>
        if (connectors.contains(service)) connectors.remove(service)
        throw e
    }
  }
}

/**
 * REST interface to Akka's statistics recorder.
 * <p/>
 * Here is an example that retreives a statistics report. 
 * <pre>
 * http://localhost:9998/stats?reset=true
 * </pre>
 */
@Path("/stats")
class StatisticsReporter extends Actor with Logging {
  private case class Stats(reset: Boolean)
  @GET
  @Produces(Array("text/html"))
  def stats(@QueryParam("reset") reset: String): scala.xml.Elem = 
    (this !! Stats(java.lang.Boolean.valueOf(reset).booleanValue)).getOrElse(<h3>Error in REST JMX management service</h3>)

  override def receive: PartialFunction[Any, Unit] = {
    case Stats(reset) => reply(<pre>{Management.getStats(reset)}</pre>)
  }
}

class RestfulJMXBoot extends Logging {
  log.info("Booting Restful JMX servivce")
  object factory extends SupervisorFactory {
    override def getSupervisorConfig: SupervisorConfig = {
      SupervisorConfig(
        RestartStrategy(OneForOne, 3, 100),
        Supervise(
          new RestfulJMX,
          LifeCycle(Permanent, 100)) ::
        Supervise(
          new StatisticsReporter,
          LifeCycle(Permanent, 100)) ::
        Nil)
    }
  }
  factory.newSupervisor.startSupervisor
}
