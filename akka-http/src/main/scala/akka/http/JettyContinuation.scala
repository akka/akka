/**
 * Copyright 2010 Autodesk, Inc.  All rights reserved.
 * Licensed under Apache License, Version 2.0 (the "License"); you may not use this software except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.
 */

package akka.http

import org.eclipse.jetty.server._
import org.eclipse.jetty.continuation._
import Types._
import akka.AkkaApplication

/**
 * @author Garrick Evans
 */
trait JettyContinuation extends ContinuationListener {
  import javax.servlet.http.HttpServletResponse

  def app: AkkaApplication

  val builder: () ⇒ tAsyncRequestContext
  val context: Option[tAsyncRequestContext] = Some(builder())
  def go = _continuation.isDefined

  protected val _continuation: Option[AsyncContinuation] = {

    val continuation = context.get.asInstanceOf[AsyncContinuation]

    (continuation.isInitial,
      continuation.isSuspended,
      continuation.isExpired) match {

        //
        // the fresh continuation (coming through getAsyncContinuation)
        //
        case (true, false, false) ⇒ {
          continuation.setTimeout(app.MistSettings.DefaultTimeout)

          continuation.addContinuationListener(this)
          continuation.suspend

          Some(continuation)
        }
        //
        // the fresh continuation (coming through startAsync instead)
        //
        case (true, true, false) ⇒ {

          continuation.setTimeout(app.MistSettings.DefaultTimeout)
          continuation.addContinuationListener(this)

          Some(continuation)
        }
        //
        // the timeout was reset and the continuation was resumed
        // this happens when used with getAsyncContinuation
        //
        case (false, false, false) ⇒ {

          continuation.setTimeout(continuation.getAttribute(app.MistSettings.TimeoutAttribute).asInstanceOf[Long])
          continuation.suspend
          continuation.removeAttribute(app.MistSettings.TimeoutAttribute)

          None
        }
        //
        // the timeout was reset and the continuation is still suspended
        // this happens when used with startAsync
        //
        case (false, true, false) ⇒ {

          continuation.setTimeout(continuation.getAttribute(app.MistSettings.TimeoutAttribute).asInstanceOf[Long])
          continuation.removeAttribute(app.MistSettings.TimeoutAttribute)

          None
        }
        //
        // unexpected continution state(s) - log and do nothing
        //
        case _ ⇒ {
          //continuation.cancel
          None
        }
      }
  }

  def suspended: Boolean = _continuation match {
    case None               ⇒ false
    case Some(continuation) ⇒ (continuation.isSuspended || (continuation.getAttribute(app.MistSettings.TimeoutAttribute) ne null))
  }

  def timeout(ms: Long): Boolean = _continuation match {
    case None ⇒ false
    case Some(continuation) ⇒
      continuation.setAttribute(app.MistSettings.TimeoutAttribute, ms)
      continuation.resume
      true
  }

  //
  // ContinuationListener
  //
  def onComplete(c: Continuation) = {}
  def onTimeout(c: Continuation) = {
    c.getServletResponse.asInstanceOf[HttpServletResponse].addHeader(app.MistSettings.ExpiredHeaderName, app.MistSettings.ExpiredHeaderValue)
    c.complete
  }
}

class JettyContinuationMethodFactory(_app: AkkaApplication) extends RequestMethodFactory {
  implicit val app = _app
  def Delete(f: () ⇒ tAsyncRequestContext): RequestMethod = new Delete(f) with JettyContinuation
  def Get(f: () ⇒ tAsyncRequestContext): RequestMethod = new Get(f) with JettyContinuation
  def Head(f: () ⇒ tAsyncRequestContext): RequestMethod = new Head(f) with JettyContinuation
  def Options(f: () ⇒ tAsyncRequestContext): RequestMethod = new Options(f) with JettyContinuation
  def Post(f: () ⇒ tAsyncRequestContext): RequestMethod = new Post(f) with JettyContinuation
  def Put(f: () ⇒ tAsyncRequestContext): RequestMethod = new Put(f) with JettyContinuation
  def Trace(f: () ⇒ tAsyncRequestContext): RequestMethod = new Trace(f) with JettyContinuation
}

