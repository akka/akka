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

@Path("/helloworld")
class HelloWorldResource {
  @GET
  @Produces(Array("text/plain"))
  def getClichedMessage = "Hello World"
}

class restManagerSpecTest extends JUnit4(restManagerSpec) // for JUnit4 and Maven
object restManagerSpec extends Specification {

  "test" in {
    val threadSelector = Kernel.startServer
    val reply = System.in.read
    println("==============> " + reply)
    threadSelector.stopEndpoint
  }
}