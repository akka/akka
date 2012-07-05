/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.http

import akka.actor.{ ActorRef, Actor }
import akka.event.EventHandler

import javax.servlet.http.{ HttpServletResponse, HttpServletRequest }
import javax.servlet.http.HttpServlet
import javax.servlet.Filter

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
  protected val _root = Actor.registry.actorsFor(RootActorID).head

  /**
   * Server-specific method factory
   */
  protected var _factory: Option[RequestMethodFactory] = None

  /**
   *   Handles all servlet requests
   */
  protected def mistify(request: HttpServletRequest,
                        response: HttpServletResponse)(builder: (() ⇒ tAsyncRequestContext) ⇒ RequestMethod) = {
    def suspend: tAsyncRequestContext = {

      // set to right now, which is effectively "already expired"
      response.setDateHeader("Expires", System.currentTimeMillis)
      response.setHeader("Cache-Control", "no-cache, must-revalidate")

      // no keep-alive?
      if (ConnectionClose) response.setHeader("Connection", "close")

      // suspend the request
      // TODO: move this out to the specialized support if jetty asyncstart doesnt let us update TOs
      request.asInstanceOf[tAsyncRequest].startAsync.asInstanceOf[tAsyncRequestContext]
    }

    // shoot the message to the root endpoint for processing
    // IMPORTANT: the suspend method is invoked on the server thread not in the actor
    val method = builder(suspend _)
    if (method.go) _root ! method
  }

  /**
   * Sets up what mist needs to be able to service requests
   * must be called prior to dispatching to "mistify"
   */
  def initMist(context: ServletContext) {
    val server = context.getServerInfo
    val (major, minor) = (context.getMajorVersion, context.getMinorVersion)
    _factory = if (major >= 3) {
      Some(Servlet30ContextMethodFactory)
    } else if (server.toLowerCase startsWith JettyServer) {
      Some(JettyContinuationMethodFactory)
    } else {
      None
    }
  }
}

/**
 * AkkaMistServlet adds support to bridge Http and Actors in an asynchronous fashion
 * Async impls currently supported: Servlet3.0, Jetty Continuations
 */
class AkkaMistServlet extends HttpServlet with Mist {
  import javax.servlet.{ ServletConfig }

  /**
   * Initializes Mist
   */
  override def init(config: ServletConfig) {
    super.init(config)
    initMist(config.getServletContext)
  }

  protected override def doDelete(req: HttpServletRequest, res: HttpServletResponse) = mistify(req, res)(_factory.get.Delete)
  protected override def doGet(req: HttpServletRequest, res: HttpServletResponse) = mistify(req, res)(_factory.get.Get)
  protected override def doHead(req: HttpServletRequest, res: HttpServletResponse) = mistify(req, res)(_factory.get.Head)
  protected override def doOptions(req: HttpServletRequest, res: HttpServletResponse) = mistify(req, res)(_factory.get.Options)
  protected override def doPost(req: HttpServletRequest, res: HttpServletResponse) = mistify(req, res)(_factory.get.Post)
  protected override def doPut(req: HttpServletRequest, res: HttpServletResponse) = mistify(req, res)(_factory.get.Put)
  protected override def doTrace(req: HttpServletRequest, res: HttpServletResponse) = mistify(req, res)(_factory.get.Trace)
}

/**
 * Proof-of-concept, use at own risk
 * Will be officially supported in a later release
 */
class AkkaMistFilter extends Filter with Mist {
  import javax.servlet.{ ServletRequest, ServletResponse, FilterConfig, FilterChain }

  /**
   * Initializes Mist
   */
  def init(config: FilterConfig) {
    initMist(config.getServletContext)
  }

  /**
   * Decide how/if to handle the request
   */
  override def doFilter(req: ServletRequest, res: ServletResponse, chain: FilterChain) {
    (req, res) match {
      case (hreq: HttpServletRequest, hres: HttpServletResponse) ⇒
        hreq.getMethod.toUpperCase match {
          case "DELETE"  ⇒ mistify(hreq, hres)(_factory.get.Delete)
          case "GET"     ⇒ mistify(hreq, hres)(_factory.get.Get)
          case "HEAD"    ⇒ mistify(hreq, hres)(_factory.get.Head)
          case "OPTIONS" ⇒ mistify(hreq, hres)(_factory.get.Options)
          case "POST"    ⇒ mistify(hreq, hres)(_factory.get.Post)
          case "PUT"     ⇒ mistify(hreq, hres)(_factory.get.Put)
          case "TRACE"   ⇒ mistify(hreq, hres)(_factory.get.Trace)
          case unknown   ⇒ {}
        }
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
  val Dispatcher = Dispatchers.fromConfig("akka.http.mist-dispatcher")

  type Hook = Function[String, Boolean]
  type Provider = Function[String, ActorRef]

  case class Attach(hook: Hook, provider: Provider)
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
  protected var _attachments = List[Tuple2[Hook, Provider]]()

  /**
   *
   */
  protected def _attach(hook: Hook, provider: Provider) = _attachments = (hook, provider) :: _attachments

  /**
   * Message handling common to all endpoints, must be chained
   */
  protected def handleHttpRequest: Receive = {

    // add the endpoint - the if the uri hook matches,
    // the message will be sent to the actor returned by the provider func
    case Attach(hook, provider) ⇒ _attach(hook, provider)

    // dispatch the suspended requests
    case req: RequestMethod ⇒ {
      val uri = req.request.getPathInfo
      val endpoints = _attachments.filter { _._1(uri) }

      if (!endpoints.isEmpty) endpoints.foreach { _._2(uri) ! req }
      else {
        self.sender match {
          case Some(s) ⇒ s reply NoneAvailable(uri, req)
          case None    ⇒ _na(uri, req)
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

  // use the configurable dispatcher
  self.dispatcher = Endpoint.Dispatcher

  // adopt the configured id
  if (RootActorBuiltin) self.id = RootActorID

  override def preStart() =
    _attachments = Tuple2((uri: String) ⇒ { uri eq Root }, (uri: String) ⇒ this.actor) :: _attachments

  def recv: Receive = {
    case NoneAvailable(uri, req) ⇒ _na(uri, req)
    case unknown                 ⇒ {}
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

  def getHeaderOrElse(name: String, default: Function[Any, String]): String =
    request.getHeader(name) match {
      case null ⇒ default(null)
      case s    ⇒ s
    }

  def getParameterOrElse(name: String, default: Function[Any, String]): String =
    request.getParameter(name) match {
      case null ⇒ default(null)
      case s    ⇒ s
    }

  def complete(status: Int, body: String): Boolean = complete(status, body, Headers())

  def complete(status: Int, body: String, headers: Headers): Boolean =
    rawComplete { res ⇒
      res.setStatus(status)
      headers foreach { h ⇒ response.setHeader(h._1, h._2) }
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
