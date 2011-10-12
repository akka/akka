/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import javax.servlet.{ AsyncContext, AsyncListener, AsyncEvent }
import Types._
import akka.event.EventHandler
import akka.AkkaApplication

/**
 * @author Garrick Evans
 */
trait Servlet30Context extends AsyncListener {
  import javax.servlet.http.HttpServletResponse

  protected def application: AkkaApplication

  val builder: () ⇒ tAsyncRequestContext
  val context: Option[tAsyncRequestContext] = Some(builder())
  def go = context.isDefined

  protected val _ac: AsyncContext = {
    val ac = context.get.asInstanceOf[AsyncContext]
    ac setTimeout application.MistSettings.DefaultTimeout
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
        EventHandler.error(e, this, e.getMessage)
        false
    }
  }

  //
  // AsyncListener
  //
  def onComplete(e: AsyncEvent) {}
  def onError(e: AsyncEvent) = e.getThrowable match {
    case null ⇒
    case t    ⇒ EventHandler.error(t, this, t.getMessage)
  }
  def onStartAsync(e: AsyncEvent) {}
  def onTimeout(e: AsyncEvent) = {
    e.getSuppliedResponse.asInstanceOf[HttpServletResponse].addHeader(application.MistSettings.ExpiredHeaderName, application.MistSettings.ExpiredHeaderValue)
    e.getAsyncContext.complete
  }
}

class Servlet30ContextMethodFactory(val _application: AkkaApplication) extends RequestMethodFactory {
  trait App {
    def application = _application
  }
  def Delete(f: () ⇒ tAsyncRequestContext): RequestMethod = new Delete(f) with Servlet30Context with App
  def Get(f: () ⇒ tAsyncRequestContext): RequestMethod = new Get(f) with Servlet30Context with App
  def Head(f: () ⇒ tAsyncRequestContext): RequestMethod = new Head(f) with Servlet30Context with App
  def Options(f: () ⇒ tAsyncRequestContext): RequestMethod = new Options(f) with Servlet30Context with App
  def Post(f: () ⇒ tAsyncRequestContext): RequestMethod = new Post(f) with Servlet30Context with App
  def Put(f: () ⇒ tAsyncRequestContext): RequestMethod = new Put(f) with Servlet30Context with App
  def Trace(f: () ⇒ tAsyncRequestContext): RequestMethod = new Trace(f) with Servlet30Context with App
}

