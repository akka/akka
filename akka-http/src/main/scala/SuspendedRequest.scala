/**
 * Copyright 2010 Autodesk, Inc.  All rights reserved.
 * Licensed under Apache License, Version 2.0 (the "License"); you may not use this software except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.
 */

package akka.http


import akka.util.Logging
import Types._


/**
 * @author Garrick Evans
 */
trait SuspendedRequest extends Logging
{
  import javax.servlet.http.{HttpServletResponse, HttpServletRequest}
  import org.eclipse.jetty.server._
  import org.eclipse.jetty.continuation._

  final val Timeout = "timeout"
  final val DefaultTimeout = 30000

  var context: Option[tAsyncContext] = None

  def init(suspend:()=>Option[tAsyncContext], callback:()=>Option[AnyRef]) =
  {
    suspend() match
    {
      case (Some(continuation)) =>
        {
          context = Some(continuation)
          val ac = continuation.asInstanceOf[AsyncContinuation]

          (ac.isInitial, ac.isSuspended, ac.isExpired) match
          {
                //
                // the fresh continuation
                //
            case (true, false, false) =>
              {
                ac.setTimeout(DefaultTimeout)

                //callback() foreach {listener => ac.addContinuationListener(listener)}
                //ac.addContinuationListener(this)
                ac.suspend
              }
                //
                // the timeout was reset and the continuation was resumed
                //  need to update the timeout and resuspend
                // very important to clear the context so the request is not rebroadcast to the endpoint
                //
            case (false, false, false) =>
              {
                ac.setTimeout(ac.getAttribute(Timeout).asInstanceOf[Long])
                ac.suspend
                ac.removeAttribute(Timeout)

                context = None
                log.debug("Updating and re-suspending request. TIMEOUT ("+ac.getTimeout+" ms)")
              }
                //
                // we don't actually expect to get this one here since the listener will finish him off
                //
            case (_, _, true) =>
              {
                response.setStatus(HttpServletResponse.SC_REQUEST_TIMEOUT)
                context = None
                log.warning("Expired request arrived here unexpectedly. REQUEST ("+continuation.toString+")")
              }
            case unknown =>
              {
                log.error("Unexpected continuation state detected - cancelling")
                ac.cancel
                context = None
              }
          }
        }
      case _ =>
        {
          log.error("Cannot initialize request without an asynchronous context.")
        }
    }
  }

  def request = context.get.getRequest.asInstanceOf[HttpServletRequest]
  def response = context.get.getResponse.asInstanceOf[HttpServletResponse]
  def suspended =
    {
      context match
        {
          case Some(continuation) =>
          {
            val ac = continuation.asInstanceOf[AsyncContinuation]
            (ac.isSuspended || (ac.getAttribute(Timeout) != null))
          }
          case None => false
        }
    }

  def getHeaderOrElse(name: String, default: Function[Any, String]): String =
    {
      request.getHeader(name) match
      {
        case null => default(null)
        case s => s
      }
    }

  def getParameterOrElse(name: String, default: Function[Any, String]): String =
    {
      request.getParameter(name) match
      {
        case null => default(null)
        case s => s
      }
    }

  /**
   * Allow for an updatable timeout
   */
  def timeout(ms:Long):Unit =
    {
      context match
      {
        case Some(continuation) =>
          {
            continuation.asInstanceOf[AsyncContinuation].setAttribute(Timeout, ms)
            continuation.asInstanceOf[AsyncContinuation].resume
          }
        case None => log.error("Cannot update the timeout on an unsuspended request")
      }
    }

  def complete(status: Int, body: String): Boolean = complete(status, body, List[Tuple2[String, String]]())

  def complete(status: Int, body: String, headers: List[Tuple2[String, String]]): Boolean =
    {
      var ok = false
      context match
      {
        case Some(pipe) =>
          {
            try
            {
              if (!suspended)
              {
                log.warning("Attempt to complete an expired connection.")
              }
              else
              {
                response.setStatus(status)
                headers foreach {h => response.setHeader(h._1, h._2)}
                response.getWriter.write(body)
                response.getWriter.close
                response.flushBuffer
                pipe.complete
                ok = true
              }
            }
            catch
            {
              case ex => log.error(ex, "Failed to write data to connection on resume - the client probably disconnected")
            }
            finally
            {
              context = None
            }
          }
        case None =>
          {
            log.error("Attempt to complete request with no context.  STATUS (" + status + ") BODY (" + body + ") HEADERS (" + headers + ")")
          }
      }
      ok
    }

  def complete(t: Throwable): Unit =
    {
      var status = 0
      context match
      {
        case Some(pipe) =>
          {
            try
            {
              if (!suspended)
              {
                log.warning("Attempt to complete an expired connection.")
              }
              else
              {
                status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                response.sendError(status, "Failed to write data to connection on resume")
                pipe.complete
              }
            }
            catch
            {
              case ex => log.error(ex, "Request completed with internal error.")
            }
            finally
            {
              context = None
              log.error(t, "Request completed with internal error.")
            }
          }
        case None =>
          {
            log.error(t, "Attempt to complete request with no context")
          }
      }
    }

  def onComplete(c:Continuation) = {}
  def onTimeout(c:Continuation) =
    {
      c.getServletResponse.asInstanceOf[HttpServletResponse].addHeader("Suspend","Timeout")
      c.complete
      log.debug("Request expired. CONTEXT ("+c+")")
    }


  def OK(body: String): Boolean = complete(HttpServletResponse.SC_OK, body)
  def OK(body: String, headers:List[Tuple2[String,String]]): Boolean = complete(HttpServletResponse.SC_OK, body, headers)
  def Created(body: String): Boolean = complete(HttpServletResponse.SC_CREATED, body)
  def Accepted(body: String): Boolean = complete(HttpServletResponse.SC_ACCEPTED, body)
  def NotModified(body:String): Boolean = complete(HttpServletResponse.SC_NOT_MODIFIED, body)
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


case class Delete(f:(()=>Option[tAsyncContext]), g:(()=>Option[AnyRef])) extends SuspendedRequest {init(f, g)}
case class Get(f:(()=>Option[tAsyncContext]), g:(()=>Option[AnyRef])) extends SuspendedRequest {init(f, g)}
case class Head(f:(()=>Option[tAsyncContext]), g:(()=>Option[AnyRef])) extends SuspendedRequest {init(f, g)}
case class Options(f:(()=>Option[tAsyncContext]), g:(()=>Option[AnyRef])) extends SuspendedRequest {init(f, g)}
case class Post(f:(()=>Option[tAsyncContext]), g:(()=>Option[AnyRef])) extends SuspendedRequest {init(f, g)}
case class Put(f:(()=>Option[tAsyncContext]), g:(()=>Option[AnyRef])) extends SuspendedRequest {init(f, g)}
case class Trace(f:(()=>Option[tAsyncContext]), g:(()=>Option[AnyRef])) extends SuspendedRequest {init(f, g)}

