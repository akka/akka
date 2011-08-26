/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import akka.event.EventHandler
import akka.config.ConfigurationException

import javax.servlet.http.{ HttpServletResponse, HttpServletRequest }
import javax.servlet.http.HttpServlet
import javax.servlet.Filter
import java.lang.UnsupportedOperationException
import akka.actor.{ NullChannel, ActorRef, Actor }

/**
 * @author Garrick Evans
 */
object MistSettings {
  import akka.config.Config._

  val JettyServer = "jetty"
  val TimeoutAttribute = "timeout"

  val ConnectionClose = config.getBool("akka.http.connection-close", true)
  val RootActorBuiltin = config.getBool("akka.http.root-actor-builtin", true)
  val RootActorID = config.getString("akka.http.root-actor-id", "_httproot")
  val DefaultTimeout = config.getLong("akka.http.timeout", 1000)
  val ExpiredHeaderName = config.getString("akka.http.expired-header-name", "Async-Timeout")
  val ExpiredHeaderValue = config.getString("akka.http.expired-header-value", "expired")
}

/**
 * Structural type alias's required to work with both Servlet 3.0 and Jetty's Continuation API
 *
 * @author Garrick Evans
 */
object Types {
  import javax.servlet.{ ServletRequest, ServletResponse }

  /**
   * Represents an asynchronous request
   */
  type tAsyncRequest = {
    def startAsync: tAsyncRequestContext
  }

  /**
   * Used to match both AsyncContext and AsyncContinuation in order to complete the request
   */
  type tAsyncRequestContext = {
    def complete: Unit
    def getRequest: ServletRequest
    def getResponse: ServletResponse
  }

  type Header = Tuple2[String, String]
  type Headers = List[Header]

  def Headers(): Headers = Nil
}

import Types._

/**
 *
 */
trait Mist {
  import javax.servlet.ServletContext
  import MistSettings._

  /**
   * The root endpoint actor
   */
  def root: ActorRef

  /**
   * Server-specific method factory
   */
  protected var factory: Option[RequestMethodFactory] = None

  /**
   *   Handles all servlet requests
   */
  protected def mistify(request: HttpServletRequest,
                        response: HttpServletResponse) = {

    val builder: (() ⇒ tAsyncRequestContext) ⇒ RequestMethod =
      request.getMethod.toUpperCase match {
        case "DELETE"  ⇒ factory.get.Delete
        case "GET"     ⇒ factory.get.Get
        case "HEAD"    ⇒ factory.get.Head
        case "OPTIONS" ⇒ factory.get.Options
        case "POST"    ⇒ factory.get.Post
        case "PUT"     ⇒ factory.get.Put
        case "TRACE"   ⇒ factory.get.Trace
        case unknown   ⇒ throw new UnsupportedOperationException(unknown)
      }

    def suspend(closeConnection: Boolean): tAsyncRequestContext = {

      // set to right now, which is effectively "already expired"
      response.setDateHeader("Expires", System.currentTimeMillis)
      response.setHeader("Cache-Control", "no-cache, must-revalidate")

      // no keep-alive?
      if (closeConnection) response.setHeader("Connection", "close")

      // suspend the request
      // TODO: move this out to the specialized support if jetty asyncstart doesnt let us update TOs
      request.asInstanceOf[tAsyncRequest].startAsync.asInstanceOf[tAsyncRequestContext]
    }

    // shoot the message to the root endpoint for processing
    // IMPORTANT: the suspend method is invoked on the server thread not in the actor
    val method = builder(() ⇒ suspend(ConnectionClose))
    if (method.go) root ! method
  }

  /**
   * Sets up what mist needs to be able to service requests
   * must be called prior to dispatching to "mistify"
   */
  def initMist(context: ServletContext) {
    val server = context.getServerInfo
    val (major, minor) = (context.getMajorVersion, context.getMinorVersion)
    factory = if (major >= 3) {
      Some(Servlet30ContextMethodFactory)
    } else if (server.toLowerCase startsWith JettyServer) {
      Some(JettyContinuationMethodFactory)
    } else {
      None
    }
  }
}

trait RootEndpointLocator {
  var root: ActorRef = null

  def configureRoot(address: String) {
    def findRoot(address: String): ActorRef =
      Actor.registry.actorFor(address).getOrElse(
        throw new ConfigurationException("akka.http.root-actor-id configuration option does not have a valid actor address [" + address + "]"))

    root = if ((address eq null) || address == "") findRoot(MistSettings.RootActorID) else findRoot(address)
  }
}

/**
 * AkkaMistServlet adds support to bridge Http and Actors in an asynchronous fashion
 * Async impls currently supported: Servlet3.0, Jetty Continuations
 */
class AkkaMistServlet extends HttpServlet with Mist with RootEndpointLocator {
  import javax.servlet.{ ServletConfig }

  /**
   * Initializes Mist
   */
  override def init(config: ServletConfig) {
    super.init(config)
    initMist(config.getServletContext)
    configureRoot(config.getServletContext.getInitParameter("root-endpoint"))
  }

  protected override def service(req: HttpServletRequest, res: HttpServletResponse) = mistify(req, res)
}

/**
 * Proof-of-concept, use at own risk
 * Will be officially supported in a later release
 */
class AkkaMistFilter extends Filter with Mist with RootEndpointLocator {
  import javax.servlet.{ ServletRequest, ServletResponse, FilterConfig, FilterChain }

  /**
   * Initializes Mist
   */
  def init(config: FilterConfig) {
    initMist(config.getServletContext)
    configureRoot(config.getServletContext.getInitParameter("root-endpoint"))
  }

  /**
   * Decide how/if to handle the request
   */
  override def doFilter(req: ServletRequest, res: ServletResponse, chain: FilterChain) {
    (req, res) match {
      case (hreq: HttpServletRequest, hres: HttpServletResponse) ⇒
        mistify(hreq, hres)
        chain.doFilter(req, res)
      case _ ⇒ chain.doFilter(req, res)
    }
  }

  override def destroy {}
}

///////////////////////////////////////////
//  Endpoints
///////////////////////////////////////////

object Endpoint {
  import akka.dispatch.Dispatchers

  /**
   * leverage the akka config to tweak the dispatcher for our endpoints
   */
  lazy val Dispatcher = Dispatchers.fromConfig("akka.http.mist-dispatcher")

  type Hook = PartialFunction[String, ActorRef]

  case class Attach(hook: Hook) {
    //Only here for backwards compat, can possibly be thrown away
    def this(hook: String ⇒ Boolean, provider: String ⇒ ActorRef) = this({
      case x if hook(x) ⇒ provider(x)
    })
  }

  case class NoneAvailable(uri: String, req: RequestMethod)
}

/**
 * @author Garrick Evans
 */
trait Endpoint { this: Actor ⇒

  import Endpoint._

  /**
   * A convenience method to get the actor ref
   */
  def actor: ActorRef = this.self

  /**
   * The list of connected endpoints to which this one should/could forward the request.
   * If the hook func returns true, the message will be sent to the actor returned from provider.
   */
  protected var _attachments = List[Hook]()

  /**
   *
   */
  protected def _attach(hook: Hook) = _attachments ::= hook

  /**
   * Message handling common to all endpoints, must be chained
   */
  protected def handleHttpRequest: Receive = {

    // add the endpoint - the if the uri hook matches,
    // the message will be sent to the actor returned by the provider func
    case Attach(hook) ⇒ _attach(hook)

    // dispatch the suspended requests
    case req: RequestMethod ⇒ {
      val uri = req.request.getPathInfo
      val endpoints = _attachments.filter { _ isDefinedAt uri }

      if (!endpoints.isEmpty) endpoints.foreach { _.apply(uri) ! req }
      else {
        self.channel match {
          case null | NullChannel ⇒ _na(uri, req)
          case channel            ⇒ channel ! NoneAvailable(uri, req)
        }
      }
    }
  }

  /**
   * no endpoint available - completes the request with a 404
   */
  protected def _na(uri: String, req: RequestMethod) = {
    req.NotFound("No endpoint available for [" + uri + "]")
  }
}

class RootEndpoint extends Actor with Endpoint {
  import Endpoint._
  import MistSettings._

  final val Root = "/"

  override def preStart() =
    _attachments ::= { case `Root` ⇒ this.actor }

  def recv: Receive = {
    case NoneAvailable(uri, req) ⇒ _na(uri, req)
    case unknown                 ⇒
  }

  /**
   * Note that root is a little different, other endpoints should chain their own recv first
   */
  def receive = handleHttpRequest orElse recv
}

///////////////////////////////////////////
//  RequestMethods
///////////////////////////////////////////

/**
 * Basic description of the suspended async http request.
 *      Must be mixed with some kind of specific support (e.g. servlet 3.0 or jetty continuations)
 *
 * @author Garrick Evans
 */
trait RequestMethod {
  import java.io.IOException
  import javax.servlet.http.{ HttpServletResponse, HttpServletRequest }

  // required implementations
  val builder: () ⇒ tAsyncRequestContext

  /**
   * Provides a general type for the underlying context
   *
   * @return a completable request context
   */
  val context: Option[tAsyncRequestContext]
  def go: Boolean

  /**
   * Updates (resets) the timeout
   *
   * @return true if updated, false if not supported
   */
  def timeout(ms: Long): Boolean

  /**
   * Status of the suspension
   */
  def suspended: Boolean

  //
  // convenience funcs
  //

  def request = context.get.getRequest.asInstanceOf[HttpServletRequest]
  def response = context.get.getResponse.asInstanceOf[HttpServletResponse]

  def getHeaderOrElse(name: String, default: ⇒ String): String =
    request.getHeader(name) match {
      case null ⇒ default
      case s    ⇒ s
    }

  def getParameterOrElse(name: String, default: ⇒ String): String =
    request.getParameter(name) match {
      case null ⇒ default
      case s    ⇒ s
    }

  def complete(status: Int, body: String, headers: Headers = Headers()): Boolean =
    rawComplete { res ⇒
      res.setStatus(status)
      headers foreach { case (name, value) ⇒ response.setHeader(name, value) }
      res.getWriter.write(body)
      res.getWriter.close
      res.flushBuffer
    }

  def rawComplete(completion: HttpServletResponse ⇒ Unit): Boolean =
    context match {
      case Some(pipe) ⇒
        try {
          if (!suspended) false
          else {
            completion(response)
            pipe.complete
            true
          }
        } catch {
          case io: Exception ⇒
            EventHandler.error(io, this, io.getMessage)
            false
        }
      case None ⇒ false
    }

  def complete(t: Throwable) {
    context match {
      case Some(pipe) ⇒
        try {
          if (suspended) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to write data to connection on resume")
            pipe.complete
          }
        } catch {
          case io: IOException ⇒
            EventHandler.error(io, this, io.getMessage)
        }
      case None ⇒ {}
    }
  }

  /*
   * Utility methods to send responses back
   */
  def OK(body: String): Boolean = complete(HttpServletResponse.SC_OK, body)
  def OK(body: String, headers: Headers): Boolean = complete(HttpServletResponse.SC_OK, body, headers)
  def Created(body: String): Boolean = complete(HttpServletResponse.SC_CREATED, body)
  def Accepted(body: String): Boolean = complete(HttpServletResponse.SC_ACCEPTED, body)
  def NotModified(body: String): Boolean = complete(HttpServletResponse.SC_NOT_MODIFIED, body)
  def BadRequest(body: String): Boolean = complete(HttpServletResponse.SC_BAD_REQUEST, body)
  def Unauthorized(body: String): Boolean = complete(HttpServletResponse.SC_UNAUTHORIZED, body)
  def Forbidden(body: String): Boolean = complete(HttpServletResponse.SC_FORBIDDEN, body)
  def NotAllowed(body: String): Boolean = complete(HttpServletResponse.SC_METHOD_NOT_ALLOWED, body)
  def NotFound(body: String): Boolean = complete(HttpServletResponse.SC_NOT_FOUND, body)
  def Timeout(body: String): Boolean = complete(HttpServletResponse.SC_REQUEST_TIMEOUT, body)
  def Conflict(body: String): Boolean = complete(HttpServletResponse.SC_CONFLICT, body)
  def UnsupportedMediaType(body: String): Boolean = complete(HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, body)
  def Error(body: String): Boolean = complete(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, body)
  def NotImplemented(body: String): Boolean = complete(HttpServletResponse.SC_NOT_IMPLEMENTED, body)
  def Unavailable(body: String, retry: Int): Boolean = complete(HttpServletResponse.SC_SERVICE_UNAVAILABLE, body, List(("Retry-After", retry.toString)))
}

abstract class Delete(val builder: () ⇒ tAsyncRequestContext) extends RequestMethod
abstract class Get(val builder: () ⇒ tAsyncRequestContext) extends RequestMethod
abstract class Head(val builder: () ⇒ tAsyncRequestContext) extends RequestMethod
abstract class Options(val builder: () ⇒ tAsyncRequestContext) extends RequestMethod
abstract class Post(val builder: () ⇒ tAsyncRequestContext) extends RequestMethod
abstract class Put(val builder: () ⇒ tAsyncRequestContext) extends RequestMethod
abstract class Trace(val builder: () ⇒ tAsyncRequestContext) extends RequestMethod

trait RequestMethodFactory {
  def Delete(f: () ⇒ tAsyncRequestContext): RequestMethod
  def Get(f: () ⇒ tAsyncRequestContext): RequestMethod
  def Head(f: () ⇒ tAsyncRequestContext): RequestMethod
  def Options(f: () ⇒ tAsyncRequestContext): RequestMethod
  def Post(f: () ⇒ tAsyncRequestContext): RequestMethod
  def Put(f: () ⇒ tAsyncRequestContext): RequestMethod
  def Trace(f: () ⇒ tAsyncRequestContext): RequestMethod
}
