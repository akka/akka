/**
 * Copyright 2010 Autodesk, Inc.  All rights reserved.
 * Licensed under Apache License, Version 2.0 (the "License"); you may not use this software except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.
 */

package akka.http

import akka.util.Logging
import javax.servlet.http.HttpServlet

/**
 * @author Garrick Evans
 */
class AkkaHttpServlet extends HttpServlet with Logging
{
  import java.util. {Date, TimeZone}
  import java.text.SimpleDateFormat
  import javax.servlet.ServletConfig
  import javax.servlet.http.{HttpServletRequest, HttpServletResponse}
  import akka.actor.ActorRegistry
  import Types._
  import AkkaHttpServlet._


  /**
   * The root endpoint actor
   */
  protected val _root = ActorRegistry.actorsFor(RootActorID).head

  /**
   * Server-specific method factory
   */
  protected var _factory:Option[RequestMethodFactory] = None

  /**
   *   Handles all servlet requests
   */
  protected def _do(request:HttpServletRequest, response:HttpServletResponse)(builder:(() => tAsyncRequestContext) => RequestMethod) =
  {
    def suspend:tAsyncRequestContext = {
        //
        // set to right now, which is effectively "already expired"
        //

      response.setDateHeader("Expires", System.currentTimeMillis)
      response.setHeader("Cache-Control", "no-cache, must-revalidate")

        //
        // no keep-alive?
        //
      if (ConnectionClose) response.setHeader("Connection","close")

        //
        // suspend the request
        // TODO: move this out to the specialized support if jetty asyncstart doesnt let us update TOs
        //
      request.asInstanceOf[tAsyncRequest].startAsync.asInstanceOf[tAsyncRequestContext]
    }

      //
      // shoot the message to the root endpoint for processing
      // IMPORTANT: the suspend method is invoked on the server thread not in the actor
      //
    val method = builder(suspend _)
    if (method.go) _root ! method
  }


  //
  // HttpServlet API
  //

  override def init(config: ServletConfig) =
  {
    super.init(config)

    val context = config.getServletContext
    val server = context.getServerInfo
    val (major, minor) = (context.getMajorVersion, context.getMinorVersion)

    log.info("Initializing Akka HTTP on "+server+" with Servlet API "+major+"."+minor)

    (major, minor) match {

      case (3,0) => {
        log.info("Supporting Java asynchronous contexts.")
        _factory = Some(Servlet30ContextMethodFactory)
      }

      case _ if (server.toLowerCase startsWith JettyServer) => {

        log.info("Supporting Jetty asynchronous continuations.")
        _factory = Some(JettyContinuationMethodFactory)
      }

      case _ => {
        log.error("No asynchronous request handling can be supported.")
      }
    }
  }

  protected override def  doDelete(request: HttpServletRequest, response: HttpServletResponse) = _do(request, response)(_factory.get.Delete)
  protected override def     doGet(request: HttpServletRequest, response: HttpServletResponse) = _do(request, response)(_factory.get.Get)
  protected override def    doHead(request: HttpServletRequest, response: HttpServletResponse) = _do(request, response)(_factory.get.Head)
  protected override def doOptions(request: HttpServletRequest, response: HttpServletResponse) = _do(request, response)(_factory.get.Options)
  protected override def    doPost(request: HttpServletRequest, response: HttpServletResponse) = _do(request, response)(_factory.get.Post)
  protected override def     doPut(request: HttpServletRequest, response: HttpServletResponse) = _do(request, response)(_factory.get.Put)
  protected override def   doTrace(request: HttpServletRequest, response: HttpServletResponse) = _do(request, response)(_factory.get.Trace)
}

object AkkaHttpServlet
{
  import akka.config.Config._

  final val JettyServer = "jetty"
  final val TimeoutAttribute = "timeout"

  val ConnectionClose = config.getBool("akka.rest.connection-close", true)
  val RootActorBuiltin = config.getBool("akka.rest.root-actor-builtin", true)
  val RootActorID = config.getString("akka.rest.root-actor-id", "_httproot")
  val DefaultTimeout = config.getLong("akka.rest.timeout", 1000)
  val ExpiredHeaderName = config.getString("akka.rest.expired-header-name", "Async-Timeout")
  val ExpiredHeaderValue = config.getString("akka.rest.expired-header-value", "expired")
}



