/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.client

import akka.actor._
import akka.http.model
import model.{ HttpRequest, Uri }

/**
 * INTERNAL API
 *
 * A wrapper around a [[HttpHostConnector]] that is connected to a proxy. Fixes missing Host headers and
 * relative URIs or otherwise warns if these differ from the target host/port.
 */
private[http] class ProxiedHostConnector(host: String, port: Int, proxyConnector: ActorRef) extends Actor with ActorLogging {

  import Uri._
  val authority = Authority(Host(host), port).normalizedForHttp()
  val hostHeader = model.headers.Host(host, authority.port)

  context.watch(proxyConnector)

  def receive: Receive = {
    case request: HttpRequest ⇒
      val headers = request.header[model.headers.Host] match {
        case Some(reqHostHeader) ⇒
          if (authority != Authority(reqHostHeader.host, reqHostHeader.port).normalizedForHttp())
            log.warning(s"sending request with header '$reqHostHeader' to a proxied connection to $authority")
          request.headers
        case None ⇒ request.headers :+ hostHeader
      }
      val effectiveUri =
        if (request.uri.isRelative)
          request.uri.toEffectiveHttpRequestUri(authority.host, port)
        else {
          if (authority != request.uri.authority.normalizedForHttp())
            log.warning(s"sending request with absolute URI '${request.uri}' to a proxied connection to $authority")
          request.uri
        }
      proxyConnector.forward(request.copy(uri = effectiveUri).withHeaders(headers))

    case HttpHostConnector.DemandIdleShutdown ⇒
      proxyConnector ! PoisonPill
      context.stop(self)

    case Terminated(`proxyConnector`) ⇒
      context.stop(self)

    case x ⇒ proxyConnector.forward(x)
  }
}

private[http] object ProxiedHostConnector {
  def props(host: String, port: Int, proxyConnector: ActorRef) =
    Props(classOf[ProxiedHostConnector], host, port, proxyConnector)
}