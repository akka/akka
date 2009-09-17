/*
 * Copyright 2007-2008 WorldWide Conferencing, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 */

/*
 * AKKA AAS (Authentication and Authorization Service)
 * Rework of lift's (www.liftweb.com) HTTP Authentication module
 * All cred to the Lift team (www.liftweb.com), especially David Pollak and Tim Perrett
 */

package se.scalablesolutions.akka.security

import _root_.se.scalablesolutions.akka.actor.{Scheduler,Actor,ActorRegistry}
import _root_.se.scalablesolutions.akka.state.{TransactionalState,PersistentStorageConfig}
import _root_.se.scalablesolutions.akka.util.{Logging}

import _root_.com.sun.jersey.api.model.AbstractMethod
import _root_.com.sun.jersey.spi.container.{ResourceFilterFactory,ContainerRequest,ContainerRequestFilter,ContainerResponse,ContainerResponseFilter,ResourceFilter}
import _root_.com.sun.jersey.core.util.Base64
import _root_.javax.ws.rs.core.{SecurityContext,Context,Response}
import _root_.javax.ws.rs.WebApplicationException
import _root_.javax.annotation.security.{DenyAll,PermitAll,RolesAllowed}
import _root_.java.security.Principal
import _root_.java.util.concurrent.TimeUnit

import _root_.net.liftweb.util.{SecurityHelpers, StringHelpers,IoHelpers}

object Enc extends SecurityHelpers with StringHelpers with IoHelpers

case object OK

/**
 * Authenticate represents a message to authenticate a request
 */
case class Authenticate(val req : ContainerRequest, val rolesAllowed : List[String])

/**
 * User info represents a sign-on with associated credentials/roles
 */
case class UserInfo(val username : String,val password : String,val roles : List[String])



trait Credentials

case class BasicCredentials(username : String, password : String) extends Credentials

case class DigestCredentials(method: String,
                             userName: String,
                             realm: String,
                             nonce: String,
                             uri: String,
                             qop: String,
                             nc: String,
                             cnonce: String,
                             response: String,
                             opaque: String) extends Credentials

/**
 * Jersey Filter for invocation intercept and authorization/authentication
 */
class AkkaSecurityFilterFactory extends ResourceFilterFactory with Logging {

    class Filter(actor : Actor,rolesAllowed : Option[List[String]]) extends ResourceFilter with ContainerRequestFilter with Logging {

        override def getRequestFilter : ContainerRequestFilter = this
        override def getResponseFilter : ContainerResponseFilter = null

        /**
         * Here's where the magic happens. The request is authenticated by
         * sending a request for authentication to the configured authenticator actor
         */
        override def filter(request : ContainerRequest) : ContainerRequest =
            rolesAllowed match {
                case Some(roles) => {

                    val result : AnyRef = (authenticator !? Authenticate(request,roles))

                    result match {
                        case OK => request
                        case r if r.isInstanceOf[Response] =>
                            throw new WebApplicationException(r.asInstanceOf[Response])
                        case x => {
                            log.error("Authenticator replied with unexpected result: ",x);
                            throw new WebApplicationException(Response.Status.INTERNAL_SERVER_ERROR)
                        }
                    }
                }
                case None => throw new WebApplicationException(Response.Status.FORBIDDEN)
            }
    }

    lazy val authenticatorFQN = akka.Config.config.getString("akka.rest.authenticator").getOrElse(throw new IllegalStateException("akka.rest.authenticator"))

    /**
     * Currently we always take the first, since there usually should be at most one authentication actor, but a round-robin
     * strategy could be implemented in the future
     */
    def authenticator : Actor = ActorRegistry.actorsFor(authenticatorFQN).head

    def mkFilter(roles : Option[List[String]]) : java.util.List[ResourceFilter] = java.util.Collections.singletonList(new Filter(authenticator,roles))

    /**
     * The create method is invoked for each resource, and we look for javax.annotation.security annotations
     * and create the appropriate Filter configurations for each.
     */
    override def create(am : AbstractMethod) : java.util.List[ResourceFilter] = {

        //DenyAll takes precedence
        if (am.isAnnotationPresent(classOf[DenyAll]))
            return mkFilter(None)

        //Method-level RolesAllowed takes precedence
        val ra = am.getAnnotation(classOf[RolesAllowed])

        if (ra ne null)
            return mkFilter(Some(ra.value.toList))

        //PermitAll takes precedence over resource-level RolesAllowed annotation
        if (am.isAnnotationPresent(classOf[PermitAll]))
            return null;

        //Last but not least, the resource-level RolesAllowed
        val cra = am.getResource.getAnnotation(classOf[RolesAllowed])
        if (cra ne null)
            return mkFilter(Some(ra.value.toList))

        return null;
    }
}

/**
 * AuthenticationActor is the super-trait for actors doing Http authentication
 * It defines the common ground and the flow of execution
 */
trait AuthenticationActor[C <: Credentials] extends Actor with Logging
{
    type Req = ContainerRequest

    //What realm does the authentication use?
    def realm : String

    //Creates a response to signal unauthorized
    def unauthorized : Response

    //Used to extract information from the request, returns None if no credentials found
    def extractCredentials(r : Req) : Option[C]

    //returns None is unverified
    def verify(c : Option[C]) : Option[UserInfo]

    //Contruct a new SecurityContext from the supplied parameters
    def mkSecurityContext(r : Req, user : UserInfo) : SecurityContext

    //This is the default security context factory
    def mkDefaultSecurityContext(r : Req,u : UserInfo, scheme : String) : SecurityContext = {
        val n = u.username
        val p = new Principal { def getName = n }
        
        new SecurityContext {
            def getAuthenticationScheme = scheme
            def getUserPrincipal = p
            def isSecure = r.isSecure
            def isUserInRole(role : String) = u.roles.exists(_ == role)
        }
    }

    /**
     * Responsible for the execution flow of authentication
     *
     * Credentials are extracted and verified from the request,
     * and a se3curity context is created for the ContainerRequest
     * this should ensure good integration with current Jersey security
     */
    protected val authenticate: PartialFunction[Any,Unit] = {
        case Authenticate(req,roles) => {
                    verify(extractCredentials(req)) match {
                        case Some(u : UserInfo) => {

                                req.setSecurityContext(mkSecurityContext(req,u))

                                if(roles.exists(req.isUserInRole(_)))
                                    reply(OK)
                                else
                                    reply(Response.status(Response.Status.FORBIDDEN).build)
                        }
                        case _ => reply(unauthorized)
                    }
        }
    }

    override def receive: PartialFunction[Any, Unit] = authenticate

    //returns the string value of the "Authorization"-header of the request
    def auth(r : Req) = r.getHeaderValue("Authorization")

    //Turns the aforementioned header value into an option
    def authOption(r : Req) : Option[String] = {
        val a = auth(r)
        if(a != null && a.length > 0) Some(a) else None
    }
}

/**
 * This trait implements the logic for Http Basic authentication
 * mix this trait into a class to create an authenticator
 * Don't forget to set the authenticator FQN in the rest-part of the akka config
 */
trait BasicAuthenticationActor extends AuthenticationActor[BasicCredentials]
{
    override def unauthorized =
            Response.status(401).header("WWW-Authenticate","Basic realm=\"" + realm + "\"").build

    override def extractCredentials(r : Req) : Option[BasicCredentials] = {
        val a = r.getHeaderValue("Authorization")
        new String(Base64.decode(a.substring(6,a.length).getBytes)).split(":").toList match {
        case userName :: password :: _ => Some(BasicCredentials(userName, password))
        case userName :: Nil =>           Some(BasicCredentials(userName, ""))
        case _ => None
      }
    }

    override def mkSecurityContext(r : Req,u : UserInfo) : SecurityContext =
        mkDefaultSecurityContext(r,u,SecurityContext.BASIC_AUTH)
}

/**
 * This trait implements the logic for Http Digest authentication
 * mix this trait into a class to create an authenticator
 * Don't forget to set the authenticator FQN in the rest-part of the akka config
 */
trait DigestAuthenticationActor extends AuthenticationActor[DigestCredentials]
{
    import Enc._

    private object InvalidateNonces

    //Holds the generated nonces for the specified validity period
    val nonceMap = mkNonceMap

    //Discards old nonces
    protected val invalidateNonces: PartialFunction[Any,Unit] = {
        case InvalidateNonces =>
        {
            val ts = System.currentTimeMillis

            nonceMap.retain((k,v) => (ts - v) < nonceValidityPeriod)
        }

        case e => log.info("Don't know what to do with: " + e)
    }

    //Schedule the invalidation of nonces
    Scheduler.schedule(this, InvalidateNonces, noncePurgeInterval, noncePurgeInterval, TimeUnit.MILLISECONDS )

    //authenticate or invalidate nonces
    override def receive: PartialFunction[Any, Unit] = authenticate orElse invalidateNonces

    override def unauthorized : Response =
    {
        val nonce = randomString(64);
        nonceMap.put(nonce,System.currentTimeMillis)
        unauthorized(nonce,"auth",randomString(64))
    }

    def unauthorized(nonce : String, qop : String, opaque : String) : Response =
    {
        Response.status(401).header("WWW-Authenticate",
                                            "Digest realm=\"" + realm + "\", " +
                                            "qop=\"" + qop + "\", " +
                                            "nonce=\"" + nonce + "\", " +
                                            "opaque=\"" + opaque + "\"").build
    }

    //Tests wether the specified credentials are valid
    def validate(auth: DigestCredentials,user : UserInfo) : Boolean = {
            def h(s : String) = hexEncode(md5(s.getBytes("UTF-8")))

            val ha1 = h(auth.userName + ":" + auth.realm  + ":" + user.password)
            val ha2 = h(auth.method   + ":" + auth.uri)

            val response = h(ha1      + ":" + auth.nonce  + ":" +
                             auth.nc  + ":" + auth.cnonce + ":" +
                             auth.qop + ":" + ha2)

            (response == auth.response) && (nonceMap.getOrElse(auth.nonce, -1) != -1)
      }

    override def verify(odc : Option[DigestCredentials]) : Option[UserInfo] = odc match {
        case Some(dc) => {
                userInfo(dc.userName) match {
                    case Some(u) if validate(dc,u) =>
                                  nonceMap.get(dc.nonce).map( t => (System.currentTimeMillis - t) < nonceValidityPeriod ).map(_ => u)
                    case _ => None
                }
        }
        case _ => None
    }

    override def extractCredentials(r : Req) : Option[DigestCredentials] =
    {
        authOption(r).map( s => {
                val ? = splitNameValuePairs(s.substring(7,s.length ))

                DigestCredentials(r.getMethod.toUpperCase,  ?("username"), ?("realm"),    ?("nonce"),
                                                            ?("uri"),      ?("qop"),      ?("nc"),
                                                            ?("cnonce"),   ?("response"), ?("opaque"))
            })
    }

    override def mkSecurityContext(r : Req,u : UserInfo) : SecurityContext =
        mkDefaultSecurityContext(r,u,SecurityContext.DIGEST_AUTH)

    //Mandatory overrides
    def userInfo(username : String) : Option[UserInfo]

    def mkNonceMap : scala.collection.mutable.Map[String,Long]

    //Optional overrides
    def nonceValidityPeriod = 60*1000//ms
    def noncePurgeInterval = 2*60*1000 //ms
}