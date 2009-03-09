/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package com.scalablesolutions.akka.kernel

import org.specs.runner.JUnit4
import org.specs.Specification
import javax.ws.rs.{Produces, Path, GET}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class restManagerSpecTest extends JUnit4(restManagerSpec) // for JUnit4 and Maven
object restManagerSpec extends Specification {

  "jersey server should be able to start and stop" in {
    val threadSelector = Kernel.startJersey
    threadSelector.stopEndpoint
  }
}

@Path("/helloworld")
class HelloWorldResource {
  @GET
  @Produces(Array("text/plain"))
  def getMessage = "Hello World"
}
