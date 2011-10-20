/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import javax.servlet.{ AsyncContext, AsyncListener, AsyncEvent }
import Types._
import akka.AkkaApplication

/**
 * @author Garrick Evans
 */
trait Servlet30Context extends AsyncListener {
  import javax.servlet.http.HttpServletResponse

  def app: AkkaApplication

  val builder: () ⇒ tAsyncRequestContext
  val context: Option[tAsyncRequestContext] = Some(builder())
  def go = context.isDefined

  protected val _ac: AsyncContext = {
    val ac = context.get.asInstanceOf[AsyncContext]
    ac setTimeout app.MistSettings.DefaultTimeout
    ac addListener this
    ac
  }

  def suspended = true

  def timeout(ms: Long): Boolean = {
    try {
      _ac setTimeout ms
      true
    } catch {
      case e: IllegalStateException ⇒
        app.eventHandler.error(e, this, e.getMessage)
        false
    }
  }

  //
  // AsyncListener
  //
  def onComplete(e: AsyncEvent) {}
  def onError(e: AsyncEvent) = e.getThrowable match {
    case null ⇒
    case t    ⇒ app.eventHandler.error(t, this, t.getMessage)
  }
  def onStartAsync(e: AsyncEvent) {}
  def onTimeout(e: AsyncEvent) = {
    e.getSuppliedResponse.asInstanceOf[HttpServletResponse].addHeader(app.MistSettings.ExpiredHeaderName, app.MistSettings.ExpiredHeaderValue)
    e.getAsyncContext.complete
  }
}

class Servlet30ContextMethodFactory(_app: AkkaApplication) extends RequestMethodFactory {
  implicit val app = _app
  def Delete(f: () ⇒ tAsyncRequestContext): RequestMethod = new Delete(f) with Servlet30Context
  def Get(f: () ⇒ tAsyncRequestContext): RequestMethod = new Get(f) with Servlet30Context
  def Head(f: () ⇒ tAsyncRequestContext): RequestMethod = new Head(f) with Servlet30Context
  def Options(f: () ⇒ tAsyncRequestContext): RequestMethod = new Options(f) with Servlet30Context
  def Post(f: () ⇒ tAsyncRequestContext): RequestMethod = new Post(f) with Servlet30Context
  def Put(f: () ⇒ tAsyncRequestContext): RequestMethod = new Put(f) with Servlet30Context
  def Trace(f: () ⇒ tAsyncRequestContext): RequestMethod = new Trace(f) with Servlet30Context
}

