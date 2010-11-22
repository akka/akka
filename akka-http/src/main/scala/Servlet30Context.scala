/**
 * Copyright 2010 Autodesk, Inc.  All rights reserved.
 * Licensed under Apache License, Version 2.0 (the "License"); you may not use this software except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.
 */

package akka.http

import javax.servlet. {AsyncContext, AsyncListener, AsyncEvent};
import Types._


/**
 * @author Garrick Evans
 */
trait Servlet30Context extends AsyncListener with akka.util.Logging
{
  import javax.servlet.http.HttpServletResponse
  import AkkaHttpServlet._

  val builder: () => tAsyncRequestContext
  val context: Option[tAsyncRequestContext] = Some(builder())
  def go = context.isDefined

  protected val _ac: AsyncContext = {
    val ac = context.get.asInstanceOf[AsyncContext]
    ac setTimeout DefaultTimeout
    ac addListener this
    ac
  }

  def suspended = true

  def timeout(ms:Long):Boolean = {
    try {
      _ac setTimeout ms
      true
    }
    catch {
      case ex:IllegalStateException =>
        log.info("Cannot update timeout - already returned to container")
        false
    }
  }

  //
  // AsyncListener
  //
  def onComplete(e:AsyncEvent) = {}
  def onError(e:AsyncEvent) = e.getThrowable match {
    case null => log.warning("Error occured...")
    case    t => log.warning(t, "Error occured")
  }
  def onStartAsync(e:AsyncEvent) = {}
  def onTimeout(e:AsyncEvent) = {
    e.getSuppliedResponse.asInstanceOf[HttpServletResponse].addHeader(ExpiredHeaderName, ExpiredHeaderValue)
    e.getAsyncContext.complete
  }
}

object Servlet30ContextMethodFactory extends RequestMethodFactory {
  def  Delete(f: () => tAsyncRequestContext): RequestMethod = new Delete(f) with Servlet30Context
  def     Get(f: () => tAsyncRequestContext): RequestMethod = new Get(f) with Servlet30Context
  def    Head(f: () => tAsyncRequestContext): RequestMethod = new Head(f) with Servlet30Context
  def Options(f: () => tAsyncRequestContext): RequestMethod = new Options(f) with Servlet30Context
  def    Post(f: () => tAsyncRequestContext): RequestMethod = new Post(f) with Servlet30Context
  def     Put(f: () => tAsyncRequestContext): RequestMethod = new Put(f) with Servlet30Context
  def   Trace(f: () => tAsyncRequestContext): RequestMethod = new Trace(f) with Servlet30Context
}


