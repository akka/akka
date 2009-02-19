/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package com.scalablesolutions.akka.kernel

import com.sun.grizzly.http.SelectorThread
import com.sun.jersey.api.container.grizzly.GrizzlyWebContainerFactory
import java.io.IOException
import java.net.URI
import java.util.{Map, HashMap}
import javax.ws.rs.core.UriBuilder
import javax.ws.rs.{Produces, Path, GET}

object Kernel extends Logging {

  val SERVER_URL = "http://localhost/"
  val SERVER_PORT = 9998
  val BASE_URI = UriBuilder.fromUri(SERVER_URL).port(getPort(SERVER_PORT)).build()

  def getPort(defaultPort: Int) = {
    val port = System.getenv("JERSEY_HTTP_PORT")
    if (null != port) Integer.parseInt(port)
    else defaultPort;
  }

//  @GET
//  @Produces("application/json")
//  @Path("/network/{id: [0-9]+}/{nid}")
//  def getUserByNetworkId(@PathParam {val value = "id"} id: Int, @PathParam {val value = "nid"} networkId: String): User = {
//    val q = em.createQuery("SELECT u FROM User u WHERE u.networkId = :id AND u.networkUserId = :nid")
//    q.setParameter("id", id)
//    q.setParameter("nid", networkId)
//    q.getSingleResult.asInstanceOf[User]
//  }

  def startServer: SelectorThread = {
    val initParams = new java.util.HashMap[String, String]
    initParams.put(
      "com.sun.jersey.config.property.packages",
      "com.scalablesolutions.akka.kernel")
    log.info("Starting grizzly...")
    GrizzlyWebContainerFactory.create(BASE_URI, initParams)
  }

  def main(args: Array[String]) {
    val threadSelector = startServer
    log.info("Akka kernel started at s%", BASE_URI)
    System.in.read
    threadSelector.stopEndpoint
  }
}
