/**
 * Copyright 2010 Autodesk, Inc.  All rights reserved.
 * Licensed under Apache License, Version 2.0 (the "License"); you may not use this software except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.
 */

package akka.http

import akka.util.Logging
import javax.servlet.http.{HttpServletResponse, HttpServlet}

/**
 * @author Garrick Evans
 */
class AsyncHttpServlet extends HttpServlet with Logging
{
  import java.util. {Date, TimeZone}
  import java.text.SimpleDateFormat
  import javax.servlet.ServletConfig
  import javax.servlet.http.{HttpServletRequest, HttpServletResponse}
  import akka.actor.ActorRegistry
  import Types._

  //
  // the root endpoint for this servlet will have been booted already
  //  use the system property to find out the actor id and cache him
  //  TODO: currently this is hardcoded but really use a property
  //
  protected val _root = ActorRegistry.actorsFor("DefaultGridRoot").head

  /**
   * Handles the HTTP request method on the servlet, suspends the connection and sends the asynchronous context
   * along to the root endpoint in a SuspendedRequest message
   */
  protected def _do(request:HttpServletRequest, response:HttpServletResponse)(builder: (()=>Option[tAsyncContext]) => SuspendedRequest) =
    {
      def suspend:Option[tAsyncContext] =
        {
            //
            // set to effectively "already expired"
            //
          val gmt = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss z")
              gmt.setTimeZone(TimeZone.getTimeZone("GMT"))

          response.setHeader("Expires", gmt.format(new Date))
          response.setHeader("Cache-Control", "no-cache, must-revalidate")
          response.setHeader("Connection","close")

          Some(request.asInstanceOf[tAsyncRequest].startAsync)
        }

      //
      // shoot the message to the root endpoint for processing
      // IMPORTANT: the suspend method is invoked on the jetty thread not in the actor
      //
      val msg = builder(suspend _)
      if (msg.context ne None) {_root ! msg}
    }

  /**
   * Subclasses can choose to have the servlet listen to the async context events
   * @return A type of either AsyncListener or ContinuationListener
   */
  def hook:Option[AnyRef] = None


  //
  // HttpServlet API
  //

  final val Jetty7Server = "Jetty(7"

  override def init(config: ServletConfig) =
    {
      super.init(config)

      val context = config.getServletContext
      val server = context.getServerInfo
      val (major, minor) = (context.getMajorVersion, context.getMinorVersion)

      log.debug("Initializing Akka HTTP on "+server+" with Servlet API "+major+"."+minor)

      (major, minor) match {

        case (3,0) => {
          log.debug("Supporting Java asynchronous contexts.")
        }

        case (2,5) if (server startsWith Jetty7Server) => {
          log.debug("Supporting Jetty asynchronous continuations.")

        }

        case _ => {
          log.error("No asynchronous request handling can be supported.")
        }
      }
    }

  
  protected override def doDelete(request: HttpServletRequest, response: HttpServletResponse) = _do(request, response)((f:(()=>Option[tAsyncContext])) => Delete(f, hook _))
  protected override def doGet(request: HttpServletRequest, response: HttpServletResponse) = _do(request, response)((f:(()=>Option[tAsyncContext])) => Get(f, hook _))
  protected override def doHead(request: HttpServletRequest, response: HttpServletResponse) = _do(request, response)((f:(()=>Option[tAsyncContext])) => Head(f, hook _))
  protected override def doOptions(request: HttpServletRequest, response: HttpServletResponse) = _do(request, response)((f:(()=>Option[tAsyncContext])) => Options(f, hook _))
  protected override def doPost(request: HttpServletRequest, response: HttpServletResponse) = _do(request, response)((f:(()=>Option[tAsyncContext])) => Post(f, hook _))
  protected override def doPut(request: HttpServletRequest, response: HttpServletResponse) = _do(request, response)((f:(()=>Option[tAsyncContext])) => Put(f, hook _))
  protected override def doTrace(request: HttpServletRequest, response: HttpServletResponse) = _do(request, response)((f:(()=>Option[tAsyncContext])) => Trace(f, hook _))
}

