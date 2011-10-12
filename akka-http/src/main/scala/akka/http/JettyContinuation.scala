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

  protected def application: AkkaApplication

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
          continuation.setTimeout(application.MistSettings.DefaultTimeout)

          continuation.addContinuationListener(this)
          continuation.suspend

          Some(continuation)
        }
        //
        // the fresh continuation (coming through startAsync instead)
        //
        case (true, true, false) ⇒ {

          continuation.setTimeout(application.MistSettings.DefaultTimeout)
          continuation.addContinuationListener(this)

          Some(continuation)
        }
        //
        // the timeout was reset and the continuation was resumed
        // this happens when used with getAsyncContinuation
        //
        case (false, false, false) ⇒ {

          continuation.setTimeout(continuation.getAttribute(application.MistSettings.TimeoutAttribute).asInstanceOf[Long])
          continuation.suspend
          continuation.removeAttribute(application.MistSettings.TimeoutAttribute)

          None
        }
        //
        // the timeout was reset and the continuation is still suspended
        // this happens when used with startAsync
        //
        case (false, true, false) ⇒ {

          continuation.setTimeout(continuation.getAttribute(application.MistSettings.TimeoutAttribute).asInstanceOf[Long])
          continuation.removeAttribute(application.MistSettings.TimeoutAttribute)

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
    case Some(continuation) ⇒ (continuation.isSuspended || (continuation.getAttribute(application.MistSettings.TimeoutAttribute) ne null))
  }

  def timeout(ms: Long): Boolean = _continuation match {
    case None ⇒ false
    case Some(continuation) ⇒
      continuation.setAttribute(application.MistSettings.TimeoutAttribute, ms)
      continuation.resume
      true
  }

  //
  // ContinuationListener
  //
  def onComplete(c: Continuation) = {}
  def onTimeout(c: Continuation) = {
    c.getServletResponse.asInstanceOf[HttpServletResponse].addHeader(application.MistSettings.ExpiredHeaderName, application.MistSettings.ExpiredHeaderValue)
    c.complete
  }
}

class JettyContinuationMethodFactory(val _application: AkkaApplication) extends RequestMethodFactory {
  trait App {
    def application = _application
  }
  def Delete(f: () ⇒ tAsyncRequestContext): RequestMethod = new Delete(f) with JettyContinuation with App
  def Get(f: () ⇒ tAsyncRequestContext): RequestMethod = new Get(f) with JettyContinuation with App
  def Head(f: () ⇒ tAsyncRequestContext): RequestMethod = new Head(f) with JettyContinuation with App
  def Options(f: () ⇒ tAsyncRequestContext): RequestMethod = new Options(f) with JettyContinuation with App
  def Post(f: () ⇒ tAsyncRequestContext): RequestMethod = new Post(f) with JettyContinuation with App
  def Put(f: () ⇒ tAsyncRequestContext): RequestMethod = new Put(f) with JettyContinuation with App
  def Trace(f: () ⇒ tAsyncRequestContext): RequestMethod = new Trace(f) with JettyContinuation with App
}

