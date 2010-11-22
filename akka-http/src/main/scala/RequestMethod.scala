/**
 * Copyright 2010 Autodesk, Inc.  All rights reserved.
 * Licensed under Apache License, Version 2.0 (the "License"); you may not use this software except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.
 */

package akka.http


import akka.util.Logging
import Types._


/**
 * Basic description of the suspended async http request.
 * 	Must be mixed with some kind of specific support (e.g. servlet 3.0 or jetty continuations)
 * 
 * @author Garrick Evans
 */
trait RequestMethod extends Logging
{
  import java.io.IOException
  import javax.servlet.http.{HttpServletResponse, HttpServletRequest}

  //
  // required implementations
  //

  val builder:() => tAsyncRequestContext

  /**
   * Provides a general type for the underlying context
   * 
   * @return a completable request context
   */
  val context: Option[tAsyncRequestContext]
  def go:Boolean

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
      case null => default(null)
	    case s => s
	  }

  def getParameterOrElse(name: String, default: Function[Any, String]): String =
    request.getParameter(name) match {
      case null => default(null)
      case s => s
    }


  def complete(status: Int, body: String): Boolean = complete(status, body, List[Tuple2[String, String]]())

  def complete(status: Int, body: String, headers: List[Tuple2[String, String]]): Boolean =
    context match {
      case Some(pipe) => {
        try {
          if (!suspended) {
            log.warning("Attempt to complete an expired connection.")
            false
          }
          else {
            response.setStatus(status)
            headers foreach {h => response.setHeader(h._1, h._2)}
            response.getWriter.write(body)
            response.getWriter.close
            response.flushBuffer
            pipe.complete
            true
          }
        } catch {
          case io =>
            log.error(io, "Failed to write data to connection on resume - the client probably disconnected")
            false
        }
    }
      
    case None =>
      log.error("Attempt to complete request with no context.  STATUS (" + status + ") BODY (" + body + ") HEADERS (" + headers + ")")
      false
  }

  def complete(t: Throwable) {
    context match {
      case Some(pipe) => {
        try {
          if (!suspended) {
            log.warning("Attempt to complete an expired connection.")
          }
          else {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to write data to connection on resume")
            pipe.complete
          }
        }
        catch {
          case io:IOException => log.error(io, "Request completed with internal error.")
        }
        finally {
          log.error(t, "Request completed with internal error.")
        }
      }
      
      case None =>
        log.error(t, "Attempt to complete request with no context")
    }
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

abstract class  Delete(val builder: () => tAsyncRequestContext) extends RequestMethod
abstract class     Get(val builder: () => tAsyncRequestContext) extends RequestMethod
abstract class    Head(val builder: () => tAsyncRequestContext) extends RequestMethod
abstract class Options(val builder: () => tAsyncRequestContext) extends RequestMethod
abstract class    Post(val builder: () => tAsyncRequestContext) extends RequestMethod
abstract class     Put(val builder: () => tAsyncRequestContext) extends RequestMethod
abstract class   Trace(val builder: () => tAsyncRequestContext) extends RequestMethod

trait RequestMethodFactory
{
  def  Delete(f: () => tAsyncRequestContext): RequestMethod
  def     Get(f: () => tAsyncRequestContext): RequestMethod
  def    Head(f: () => tAsyncRequestContext): RequestMethod
  def Options(f: () => tAsyncRequestContext): RequestMethod
  def    Post(f: () => tAsyncRequestContext): RequestMethod
  def     Put(f: () => tAsyncRequestContext): RequestMethod
  def   Trace(f: () => tAsyncRequestContext): RequestMethod
}
