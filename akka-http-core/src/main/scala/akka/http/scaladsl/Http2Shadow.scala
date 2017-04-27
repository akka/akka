/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl

import akka.actor.{ ActorSystem, ExtendedActorSystem, ExtensionId }
import akka.annotation.InternalApi
import akka.event.LoggingAdapter
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.http.scaladsl.settings.ServerSettings
import akka.stream.{ ActorMaterializerHelper, Materializer }

import scala.concurrent.Future

/** INTERNAL API: Uses reflection to reach for Http2 support if available or fails with an exception */
@InternalApi
private[akka] object Http2Shadow {

  type ShadowHttp2 = AnyRef
  type ShadowHttp2Ext = {
    def bindAndHandleAsync(
      handler:   HttpRequest ⇒ Future[HttpResponse],
      interface: String, port: Int,
      httpsContext: HttpsConnectionContext,
      settings:     ServerSettings,
      parallelism:  Int,
      log:          LoggingAdapter)(implicit fm: Materializer): Future[ServerBinding]
  }
  /**
   * @param httpsContext is required to be an HTTPS context, however we validate this internally,
   *                     and throw in runtime if it's not. We do this since we support the existing Http() API.
   */
  def bindAndHandleAsync(
    handler:   HttpRequest ⇒ Future[HttpResponse],
    interface: String, port: Int,
    httpsContext: ConnectionContext,
    settings:     ServerSettings,
    parallelism:  Int,
    log:          LoggingAdapter)(implicit fm: Materializer): Future[ServerBinding] = {

    val mat = ActorMaterializerHelper.downcast(fm)
    require(httpsContext.isSecure, "In order to use HTTP/2 you MUST provide an HttpsConnectionContext.")

    try {
      val system = mat.system.asInstanceOf[ExtendedActorSystem]
      val extensionIdClazz = system.dynamicAccess.getClassFor[ShadowHttp2]("akka.http.scaladsl.Http2").get

      val extensionInstance: ShadowHttp2Ext = extensionIdClazz.getMethod("get", Array(classOf[ActorSystem]): _*)
        .invoke(null, system).asInstanceOf[ShadowHttp2Ext]

      import scala.language.reflectiveCalls
      extensionInstance.bindAndHandleAsync(
        handler,
        interface, port,
        httpsContext.asInstanceOf[HttpsConnectionContext],
        settings,
        parallelism,
        log)(fm)
    } catch {
      case ex: Throwable ⇒ throw Http2SupportNotPresentException(ex)
    }
  }

  final case class Http2SupportNotPresentException(cause: Throwable)
    extends RuntimeException("Unable to invoke HTTP2 binding logic (as enabled setting `akka.http.server.use-http2`). " +
      """Please make sure that `"com.typesafe.akka" %% "akka-http2-support" % AkkaHttpVersion` is on the classpath.""", cause)

}
