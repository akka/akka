/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel

import org.specs.runner.JUnit4
import org.specs.Specification

import javax.ws.rs.{Produces, Path, GET}

//import com.sun.net.httpserver.HttpServer;
//import com.sun.ws.rest.api.client.Client;
//import com.sun.ws.rest.api.client.ClientResponse;
//import com.sun.ws.rest.api.client.ResourceProxy;

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class restManagerSpecTest extends JUnit4(restManagerSpec) // for JUnit4 and Maven
object restManagerSpec extends Specification {

  "jersey server should be able to start and stop" in {
    val threadSelector = Kernel.startJersey
/*    val cc = new DefaultClientConfig
    val c = Client.create(cc)
    val resource = c.proxy("http://localhost:9998/")
    val hello = resource.get(classOf[HelloWorldResource])
    val msg = hello.getMessage
    println("=============: " + msg)
*/    threadSelector.stopEndpoint
  }
}

@Path("/helloworld")
class HelloWorldResource {
  @GET
  @Produces(Array("text/plain"))
  def getMessage = "Hello World"
}
