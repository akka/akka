/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel

import akka.kernel.config.ActiveObjectGuiceConfigurator
import kernel.config.ScalaConfig._

import com.sun.grizzly.http.SelectorThread
import com.sun.jersey.api.client.Client
import com.sun.jersey.core.header.MediaTypes
import com.sun.jersey.api.container.grizzly.GrizzlyWebContainerFactory
import javax.ws.rs.core.UriBuilder
import javax.ws.rs.{Produces, Path, GET}

import com.google.inject.{AbstractModule, Scopes}

import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers

import com.jteigen.scalatest.JUnit4Runner
import org.junit.runner.RunWith
import org.junit.Test
import org.junit.Assert._

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
@RunWith(classOf[JUnit4Runner])
class JerseySpec extends Spec with ShouldMatchers {

  describe("A Jersey REST service") {
    it("should ...") {
      /*
      val selector = startJersey
      selector.start
      val conf = new ActiveObjectGuiceConfigurator
      conf.configure(
        RestartStrategy(AllForOne, 3, 5000),
            Component(
                classOf[resource.JerseyFoo],
                LifeCycle(Permanent, 1000),
                1000) ::
            Nil).supervise

      conf.getInstance(classOf[resource.JerseyFoo])
      */

      /*
      val client = Client.create
      val webResource = client.resource(UriBuilder.fromUri("http://localhost/").port(9998).build)
      //val webResource = client.resource("http://localhost:9998/foo")
      val responseMsg = webResource.get(classOf[String])
      responseMsg should equal ("Hello World")
      selector.stopEndpoint
    */
    }
  }

  def startJersey: SelectorThread = {
    val initParams = new java.util.HashMap[String, String]
    initParams.put("com.sun.jersey.config.property.packages", "se.scalablesolutions.akka.kernel")
    GrizzlyWebContainerFactory.create(UriBuilder.fromUri("http://localhost/").port(9998).build(), initParams)
  }
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

package resource {
  import javax.ws.rs.{Produces, Path, GET}

  class JerseyFoo {
    @GET
    @Produces(Array("application/json"))
    def foo: String = { val ret = "JerseyFoo.foo"; println(ret); ret }
  }
  @Path("/foo")
  class JerseyFooSub extends JerseyFoo
  class JerseyBar {
    def bar(msg: String) = msg + "return_bar "
  }
}
