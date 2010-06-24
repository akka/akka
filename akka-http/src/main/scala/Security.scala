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

import se.scalablesolutions.akka.actor.{Scheduler, Actor, ActorRef, ActorRegistry}
import se.scalablesolutions.akka.actor.Actor._
import se.scalablesolutions.akka.config.Config
import se.scalablesolutions.akka.util.Logging

import com.sun.jersey.api.model.AbstractMethod
import com.sun.jersey.spi.container.{ResourceFilterFactory, ContainerRequest, ContainerRequestFilter, ContainerResponse, ContainerResponseFilter, ResourceFilter}
import com.sun.jersey.core.util.Base64

import javax.ws.rs.core.{SecurityContext, Context, Response}
import javax.ws.rs.WebApplicationException
import javax.annotation.security.{DenyAll, PermitAll, RolesAllowed}
import java.security.Principal
import java.util.concurrent.TimeUnit

case object OK

/**
 * Authenticate represents a message to authenticate a request
 */
case class Authenticate(val req: ContainerRequest, val rolesAllowed: List[String])

/**
 * User info represents a sign-on with associated credentials/roles
 */
case class UserInfo(val username: String, val password: String, val roles: List[String])

trait Credentials

case class BasicCredentials(username: String, password: String) extends Credentials

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

case class SpnegoCredentials(token: Array[Byte]) extends Credentials

/**
 * Jersey Filter for invocation intercept and authorization/authentication
 */
class AkkaSecurityFilterFactory extends ResourceFilterFactory with Logging {
  class Filter(actor: ActorRef, rolesAllowed: Option[List[String]])
      extends ResourceFilter with ContainerRequestFilter with Logging {

    override def getRequestFilter: ContainerRequestFilter = this

    override def getResponseFilter: ContainerResponseFilter = null

    /**
     * Here's where the magic happens. The request is authenticated by
     * sending a request for authentication to the configured authenticator actor
     */
    override def filter(request: ContainerRequest): ContainerRequest =
      rolesAllowed match {
        case Some(roles) => {
          val result = (authenticator !! Authenticate(request, roles)).as[AnyRef]
          result match {
            case Some(OK) => request
            case Some(r) if r.isInstanceOf[Response] =>
              throw new WebApplicationException(r.asInstanceOf[Response])
            case None => throw new WebApplicationException(408)
            case unknown => {
              log.warning("Authenticator replied with unexpected result [%s]", unknown);
              throw new WebApplicationException(Response.Status.INTERNAL_SERVER_ERROR)
            }
          }
        }
        case None => throw new WebApplicationException(Response.Status.FORBIDDEN)
      }
  }

  lazy val authenticatorFQN = {
    val auth = Config.config.getString("akka.rest.authenticator", "N/A")
    if (auth == "N/A") throw new IllegalStateException("The config option 'akka.rest.authenticator' is not defined in 'akka.conf'")
    auth
  }

  /**
   * Currently we always take the first, since there usually should be at most one authentication actor, but a round-robin
   * strategy could be implemented in the future
   */
  def authenticator: ActorRef = ActorRegistry.actorsFor(authenticatorFQN).head

  def mkFilter(roles: Option[List[String]]): java.util.List[ResourceFilter] =
    java.util.Collections.singletonList(new Filter(authenticator, roles))

  /**
   * The create method is invoked for each resource, and we look for javax.annotation.security annotations
   * and create the appropriate Filter configurations for each.
   */
  override def create(am: AbstractMethod): java.util.List[ResourceFilter] = {

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
      return mkFilter(Some(cra.value.toList))

    return null;
  }
}

/**
 * AuthenticationActor is the super-trait for actors doing Http authentication
 * It defines the common ground and the flow of execution
 */
trait AuthenticationActor[C <: Credentials] extends Actor {
  type Req = ContainerRequest

  //What realm does the authentication use?
  def realm: String

  //Creates a response to signal unauthorized
  def unauthorized: Response

  //Used to extract information from the request, returns None if no credentials found
  def extractCredentials(r: Req): Option[C]

  //returns None is unverified
  def verify(c: Option[C]): Option[UserInfo]

  //Contruct a new SecurityContext from the supplied parameters
  def mkSecurityContext(r: Req, user: UserInfo): SecurityContext

  //This is the default security context factory
  def mkDefaultSecurityContext(r: Req, u: UserInfo, scheme: String): SecurityContext = {
    val n = u.username
    val p = new Principal {def getName = n}

    new SecurityContext {
      def getAuthenticationScheme = scheme
      def getUserPrincipal = p
      def isSecure = r.isSecure
      def isUserInRole(role: String) = u.roles.exists(_ == role)
    }
  }

  /**
   * Responsible for the execution flow of authentication
   *
   * Credentials are extracted and verified from the request,
   * and a se3curity context is created for the ContainerRequest
   * this should ensure good integration with current Jersey security
   */
  protected val authenticate: Receive = {
    case Authenticate(req, roles) => {
      verify(extractCredentials(req)) match {
        case Some(u: UserInfo) => {
          req.setSecurityContext(mkSecurityContext(req, u))
          if (roles.exists(req.isUserInRole(_))) self.reply(OK)
          else self.reply(Response.status(Response.Status.FORBIDDEN).build)
        }
        case _ => self.reply(unauthorized)
      }
    }
  }

  def receive = authenticate

  //returns the string value of the "Authorization"-header of the request
  def auth(r: Req) = r.getHeaderValue("Authorization")

  //Turns the aforementioned header value into an option
  def authOption(r: Req): Option[String] = {
    val a = auth(r)
    if (a != null && a.length > 0) Some(a) else None
  }
}

/**
 * This trait implements the logic for Http Basic authentication
 * mix this trait into a class to create an authenticator
 * Don't forget to set the authenticator FQN in the rest-part of the akka config
 */
trait BasicAuthenticationActor extends AuthenticationActor[BasicCredentials] {
  override def unauthorized =
    Response.status(401).header("WWW-Authenticate", "Basic realm=\"" + realm + "\"").build

  override def extractCredentials(r: Req): Option[BasicCredentials] = {
    val Authorization = """(.*):(.*)""".r

    authOption(r) match {
      case Some(token) => {
        val authResponse = new String(Base64.decode(token.substring(6).getBytes))
        authResponse match {
          case Authorization(username, password) => Some(BasicCredentials(username, password))
          case _ => None
        }
      }
      case _ => None
    }
  }

  override def mkSecurityContext(r: Req, u: UserInfo): SecurityContext =
    mkDefaultSecurityContext(r, u, SecurityContext.BASIC_AUTH)
}

/**
 * This trait implements the logic for Http Digest authentication mix this trait into a
 * class to create an authenticator. Don't forget to set the authenticator FQN in the
 * rest-part of the akka config
 */
trait DigestAuthenticationActor extends AuthenticationActor[DigestCredentials] with Logging {
  import LiftUtils._

  private object InvalidateNonces

  //Holds the generated nonces for the specified validity period
  val nonceMap = mkNonceMap

  //Discards old nonces
  protected val invalidateNonces: Receive = {
    case InvalidateNonces =>
      val ts = System.currentTimeMillis
      nonceMap.filter(tuple => (ts - tuple._2) < nonceValidityPeriod)
    case unknown =>
      log.error("Don't know what to do with: ", unknown)
  }

  //Schedule the invalidation of nonces
  Scheduler.schedule(self, InvalidateNonces, noncePurgeInterval, noncePurgeInterval, TimeUnit.MILLISECONDS)

  //authenticate or invalidate nonces
  override def receive = authenticate orElse invalidateNonces

  override def unauthorized: Response = {
    val nonce = randomString(64)
    nonceMap.put(nonce, System.currentTimeMillis)
    unauthorized(nonce, "auth", randomString(64))
  }

  def unauthorized(nonce: String, qop: String, opaque: String): Response = {
    Response.status(401).header(
      "WWW-Authenticate",
      "Digest realm=\"" + realm + "\", " +
      "qop=\"" + qop + "\", " +
      "nonce=\"" + nonce + "\", " +
      "opaque=\"" + opaque + "\"").build
  }

  //Tests wether the specified credentials are valid
  def validate(auth: DigestCredentials, user: UserInfo): Boolean = {
    def h(s: String) = hexEncode(md5(s.getBytes("UTF-8")))

    val ha1 = h(auth.userName + ":" + auth.realm + ":" + user.password)
    val ha2 = h(auth.method + ":" + auth.uri)

    val response = h(
      ha1 + ":" + auth.nonce + ":" +
      auth.nc + ":" + auth.cnonce + ":" +
      auth.qop + ":" + ha2)

    (response == auth.response) && (nonceMap.getOrElse(auth.nonce, -1) != -1)
  }

  override def verify(odc: Option[DigestCredentials]): Option[UserInfo] = odc match {
    case Some(dc) => {
      userInfo(dc.userName) match {
        case Some(u) if validate(dc, u) =>
          nonceMap.get(dc.nonce).map(t => (System.currentTimeMillis - t) < nonceValidityPeriod).map(_ => u)
        case _ => None
      }
    }
    case _ => None
  }

  override def extractCredentials(r: Req): Option[DigestCredentials] = {
    authOption(r).map(s => {
      val ? = splitNameValuePairs(s.substring(7, s.length))
      DigestCredentials(r.getMethod.toUpperCase,
        ?("username"), ?("realm"), ?("nonce"),
        ?("uri"), ?("qop"), ?("nc"),
        ?("cnonce"), ?("response"), ?("opaque"))
    })
  }

  override def mkSecurityContext(r: Req, u: UserInfo): SecurityContext =
    mkDefaultSecurityContext(r, u, SecurityContext.DIGEST_AUTH)

  //Mandatory overrides
  def userInfo(username: String): Option[UserInfo]

  def mkNonceMap: scala.collection.mutable.Map[String, Long]

  //Optional overrides
  def nonceValidityPeriod = 60 * 1000 //ms
  def noncePurgeInterval = 2 * 60 * 1000 //ms
}

import java.security.Principal
import java.security.PrivilegedActionException
import java.security.PrivilegedExceptionAction

import javax.security.auth.login.AppConfigurationEntry
import javax.security.auth.login.Configuration
import javax.security.auth.login.LoginContext
import javax.security.auth.Subject
import javax.security.auth.kerberos.KerberosPrincipal

import org.ietf.jgss.GSSContext
import org.ietf.jgss.GSSCredential
import org.ietf.jgss.GSSManager

trait SpnegoAuthenticationActor extends AuthenticationActor[SpnegoCredentials] with Logging {
  override def unauthorized =
    Response.status(401).header("WWW-Authenticate", "Negotiate").build

  // for some reason the jersey Base64 class does not work with kerberos
  // but the commons Base64 does
  import org.apache.commons.codec.binary.Base64
  override def extractCredentials(r: Req): Option[SpnegoCredentials] = {
    val AuthHeader = """Negotiate\s(.*)""".r

    authOption(r) match {
      case Some(AuthHeader(token)) =>
        Some(SpnegoCredentials(Base64.decodeBase64(token.trim.getBytes)))
      case _ => None
    }
  }


  override def verify(odc: Option[SpnegoCredentials]): Option[UserInfo] = odc match {
    case Some(dc) => {
      try {
        val principal = Subject.doAs(this.serviceSubject, new KerberosValidateAction(dc.token));
        val user = stripRealmFrom(principal)
        Some(UserInfo(user, null, rolesFor(user)))
      } catch {
        case e: PrivilegedActionException => {
          log.error(e, "Action not allowed")
          return None
        }
      }
    }
    case _ => None
  }

  override def mkSecurityContext(r: Req, u: UserInfo): SecurityContext =
    mkDefaultSecurityContext(r, u, SecurityContext.CLIENT_CERT_AUTH) // the security context does not know about spnego/kerberos
  // not sure whether to use a constant from the security context or something like "SPNEGO/Kerberos"

  /**
   * returns the roles for the given user
   */
  def rolesFor(user: String): List[String]

  // Kerberos

  /**
   * strips the realm from a kerberos principal name, returning only the user part
   */
  private def stripRealmFrom(principal: String): String = principal.split("@")(0)

  /**
   * principal name for the HTTP kerberos service, i.e HTTP/  { server } @  { realm }
   */
  lazy val servicePrincipal = {
    val p = Config.config.getString("akka.rest.kerberos.servicePrincipal", "N/A")
    if (p == "N/A") throw new IllegalStateException("The config option 'akka.rest.kerberos.servicePrincipal' is not defined in 'akka.conf'")
    p
  }

  /**
   * keytab location with credentials for the service principal
   */
  lazy val keyTabLocation = {
    val p = Config.config.getString("akka.rest.kerberos.keyTabLocation", "N/A")
    if (p == "N/A") throw new IllegalStateException("The config option 'akka.rest.kerberos.keyTabLocation' is not defined in 'akka.conf'")
    p
  }

  lazy val kerberosDebug = {
    val p = Config.config.getString("akka.rest.kerberos.kerberosDebug", "N/A")
    if (p == "N/A") throw new IllegalStateException("The config option 'akka.rest.kerberos.kerberosDebug' is not defined in 'akka.conf'")
    p
  }

  /**
   * is not used by this authenticator, so accept an empty value
   */
  lazy val realm = Config.config.getString("akka.rest.kerberos.realm", "")

  /**
   * verify the kerberos token from a client with the server
   */
  class KerberosValidateAction(kerberosTicket: Array[Byte]) extends PrivilegedExceptionAction[String] {
    def run = {
      val context = GSSManager.getInstance().createContext(null.asInstanceOf[GSSCredential])
      context.acceptSecContext(kerberosTicket, 0, kerberosTicket.length)
      val user = context.getSrcName().toString()
      context.dispose()
      user
    }
  }

  // service principal login to kerberos on startup

  val serviceSubject = servicePrincipalLogin

  /**
   * acquire an initial ticket from the kerberos server for the HTTP service
   */
  def servicePrincipalLogin = {
    val loginConfig = new LoginConfig(
      new java.net.URL(this.keyTabLocation).toExternalForm(),
      this.servicePrincipal,
      this.kerberosDebug)
    val princ = new java.util.HashSet[Principal](1)
    princ.add(new KerberosPrincipal(this.servicePrincipal))
    val sub = new Subject(false, princ, new java.util.HashSet[Object], new java.util.HashSet[Object])
    val lc = new LoginContext("", sub, null, loginConfig)
    lc.login()
    lc.getSubject()
  }

  /**
   * this class simulates a login-config.xml
   */
  class LoginConfig(keyTabLocation: String, servicePrincipal: String, debug: String) extends Configuration {
    override def getAppConfigurationEntry(name: String): Array[AppConfigurationEntry] = {
      val options = new java.util.HashMap[String, String]
      options.put("useKeyTab", "true")
      options.put("keyTab", this.keyTabLocation)
      options.put("principal", this.servicePrincipal)
      options.put("storeKey", "true")
      options.put("doNotPrompt", "true")
      options.put("isInitiator", "true")
      options.put("debug", debug)

      Array(new AppConfigurationEntry(
        "com.sun.security.auth.module.Krb5LoginModule",
        AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
        options))
    }
  }

}

/*
* Copyright 2006-2010 WorldWide Conferencing, LLC
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
object LiftUtils {
  import java.security.{MessageDigest,SecureRandom}
  val random = new SecureRandom()

  def md5(in: Array[Byte]): Array[Byte] = (MessageDigest.getInstance("MD5")).digest(in)

  /**
  * Create a random string of a given size
  * @param size size of the string to create. Must be a positive or nul integer
  * @return the generated string
  */
  def randomString(size: Int): String = {
    def addChar(pos: Int, lastRand: Int, sb: StringBuilder): StringBuilder = {
      if (pos >= size) sb
      else {
        val randNum = if ((pos % 6) == 0) random.nextInt else lastRand
        sb.append((randNum & 0x1f) match {
          case n if n < 26 => ('A' + n).toChar
          case n => ('0' + (n - 26)).toChar
        })
        addChar(pos + 1, randNum >> 5, sb)
      }
    }
    addChar(0, 0, new StringBuilder(size)).toString
  }

/** encode a Byte array as hexadecimal characters */
  def hexEncode(in: Array[Byte]): String = {
    val sb = new StringBuilder
    val len = in.length
    def addDigit(in: Array[Byte], pos: Int, len: Int, sb: StringBuilder) {
      if (pos < len) {
        val b: Int = in(pos)
        val msb = (b & 0xf0) >> 4
        val lsb = (b & 0x0f)
        sb.append((if (msb < 10) ('0' + msb).asInstanceOf[Char] else ('a' + (msb - 10)).asInstanceOf[Char]))
        sb.append((if (lsb < 10) ('0' + lsb).asInstanceOf[Char] else ('a' + (lsb - 10)).asInstanceOf[Char]))
        addDigit(in, pos + 1, len, sb)
      }
    }
    addDigit(in, 0, len, sb)
    sb.toString
  }


 /**
  * Splits a string of the form &lt;name1=value1, name2=value2, ... &gt; and unquotes the quoted values.
  * The result is a Map[String, String]
  */
  def splitNameValuePairs(props: String): Map[String, String] = {
   /**
    * If str is surrounded by quotes it return the content between the quotes
    */
    def unquote(str: String) = {
      if ((str ne null) && str.length >= 2 && str.charAt(0) == '\"' && str.charAt(str.length - 1) == '\"')
        str.substring(1, str.length - 1)
      else
        str
    }

    val list = props.split(",").toList.map(in => {
      val pair = in match { case null => Nil case s => s.split("=").toList.map(_.trim).filter(_.length > 0) }
       (pair(0), unquote(pair(1)))
    })
    val map: Map[String, String] = Map.empty
	    (map /: list)((m, next) => m + (next))
  }
}
