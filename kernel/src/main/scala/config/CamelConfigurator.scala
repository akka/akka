/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.config

import org.apache.camel.{Routes, CamelContext, Endpoint}

trait CamelConfigurator {

  /**
   * Add Camel routes for the active objects.
   * <pre>
   * activeObjectConfigurator.addRoutes(new RouteBuilder() {
   *   def configure = {
   *     from("akka:actor1").to("akka:actor2")
   *     from("akka:actor2").process(new Processor() {
   *       def process(e: Exchange) = {
   *         println("Received exchange: " + e.getIn())
   *       }
   *     })
   *   }
   * }).inject().supervise();
   * </pre>
   */
   def addRoutes(routes: Routes): ActiveObjectConfigurator

  def getCamelContext: CamelContext

  def getRoutingEndpoint(uri: String): Endpoint

  // F
  def getRoutingEndpoints: java.util.Collection[Endpoint]

  def getRoutingEndpoints(uri: String): java.util.Collection[Endpoint]
}
