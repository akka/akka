/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.security

import akka.config.Supervision._
import akka.actor.Actor._

import org.scalatest.Suite
import org.scalatest.junit.JUnitSuite
import org.scalatest.matchers.MustMatchers
import org.scalatest.mock.MockitoSugar
import org.mockito.Mockito._
import org.mockito.Matchers._
import org.junit.{ Before, After, Test }

import javax.ws.rs.core.{ SecurityContext, Context, Response }
import com.sun.jersey.spi.container.{ ResourceFilterFactory, ContainerRequest, ContainerRequestFilter, ContainerResponse, ContainerResponseFilter, ResourceFilter }
import com.sun.jersey.core.util.Base64

object BasicAuthenticatorSpec {
  class BasicAuthenticator extends BasicAuthenticationActor {
    def verify(odc: Option[BasicCredentials]): Option[UserInfo] = odc match {
      case Some(dc) ⇒ Some(UserInfo("foo", "bar", "ninja" :: "chef" :: Nil))
      case _        ⇒ None
    }
    override def realm = "test"
  }
}

class BasicAuthenticatorSpec extends junit.framework.TestCase
  with Suite with MockitoSugar with MustMatchers {
  import BasicAuthenticatorSpec._

  val authenticator = actorOf[BasicAuthenticator]
  authenticator.start()

  @Test
  def testChallenge = {
    val req = mock[ContainerRequest]

    val result = (authenticator !! (Authenticate(req, List("foo")), 10000)).as[Response].get

    // the actor replies with a challenge for the browser
    result.getStatus must equal(Response.Status.UNAUTHORIZED.getStatusCode)
    result.getMetadata.get("WWW-Authenticate").get(0).toString must startWith("Basic")
  }

  @Test
  def testAuthenticationSuccess = {
    val req = mock[ContainerRequest]
    // fake a basic auth header -> this will authenticate the user
    when(req.getHeaderValue("Authorization")).thenReturn("Basic " + new String(Base64.encode("foo:bar")))

    // fake a request authorization -> this will authorize the user
    when(req.isUserInRole("chef")).thenReturn(true)

    val result = (authenticator !! (Authenticate(req, List("chef")), 10000)).as[AnyRef].get

    result must be(OK)
    // the authenticator must have set a security context
    verify(req).setSecurityContext(any[SecurityContext])
  }

  @Test
  def testUnauthorized = {
    val req = mock[ContainerRequest]

    // fake a basic auth header -> this will authenticate the user
    when(req.getHeaderValue("Authorization")).thenReturn("Basic " + new String(Base64.encode("foo:bar")))
    when(req.isUserInRole("chef")).thenReturn(false) // this will deny access

    val result = (authenticator !! (Authenticate(req, List("chef")), 10000)).as[Response].get

    result.getStatus must equal(Response.Status.FORBIDDEN.getStatusCode)

    // the authenticator must have set a security context
    verify(req).setSecurityContext(any[SecurityContext])
  }
}

