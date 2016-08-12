/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server.directives

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.{ HostConnectionPool, OutgoingConnection }
import akka.http.scaladsl.model.Uri.Authority
import akka.http.scaladsl.model.{ HttpResponse, _ }
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.BasicDirectives._
import akka.http.scaladsl.server.directives.FutureDirectives._
import akka.http.scaladsl.server.directives.RouteDirectives._
import akka.pattern.CircuitBreaker
import akka.stream._
import akka.stream.scaladsl.{ Flow, Keep, Sink, Source }

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.util.{ Failure, Success, Try }

trait ReverseProxyDirectives {
  type RequestInterceptor = (HttpRequest) ⇒ HttpRequest
  type ResponseInterceptor = ((HttpResponse) ⇒ HttpResponse)
  type RequestExecutor = ((HttpRequest) ⇒ Future[HttpResponse])

  def completeUnmatchedPathWithReverseProxy(authority: Authority, circuitBreakerOption: Option[CircuitBreaker] = None, responseInterceptor: ResponseInterceptor = identity[HttpResponse](_))(implicit system: ActorSystem): Route = {
    forward(authority, circuitBreakerOption).tapply(x ⇒ complete(x._1.map(responseInterceptor)))
  }

  def forward(authority: Authority, circuitBreakerOption: Option[CircuitBreaker] = None)(implicit system: ActorSystem): Directive1[Try[HttpResponse]] = {
    extractUri.flatMap { uri ⇒
      val forwardUri: Uri = uri.withAuthority(authority)
      forwardToUri(forwardUri, circuitBreakerOption)
    }
  }

  def forwardUnmatchedPath(authority: Authority, circuitBreakerOption: Option[CircuitBreaker] = None)(implicit system: ActorSystem): Directive1[Try[HttpResponse]] = {
    extractUnmatchedPath.flatMap { unmatched ⇒
      extractUri.flatMap { uri ⇒
        val forwardUri: Uri = uri.withAuthority(authority).withPath(unmatched)
        forwardToUri(forwardUri, circuitBreakerOption)
      }
    }
  }

  def forwardToUri(uri: Uri, circuitBreakerOption: Option[CircuitBreaker] = None)(implicit system: ActorSystem): Directive1[Try[HttpResponse]] = {
    extractMaterializer.flatMap { implicit mat ⇒
      val requestUriInterceptor = ProxyUriRequestInterceptor(uri).andThen(ProxyHeaderRequestInterceptor)
      //TODO: select executor from configuration
      val executor = CachedHostConnectionPoolRequestExecutor()
      forwardRequest(executor, requestUriInterceptor, circuitBreakerOption)
    }
  }

  def forwardRequest(requestExecutor: RequestExecutor, requestInterceptor: RequestInterceptor, circuitBreakerOption: Option[CircuitBreaker] = None): Directive1[Try[HttpResponse]] = {
    extractExecutionContext.flatMap { implicit ec ⇒
      extractMaterializer.flatMap { implicit mat ⇒
        extractRequest.flatMap { request ⇒
          val interceptedRequest: HttpRequest = requestInterceptor(request)
          val responseFuture: Future[HttpResponse] = requestExecutor(interceptedRequest)
          circuitBreakerOption.fold(onComplete(responseFuture))(circuitBreaker ⇒ onCompleteWithBreaker(circuitBreaker)(responseFuture))
        }
      }
    }
  }
}

case class ProxyUriRequestInterceptor(val uri: Uri) extends ((HttpRequest) ⇒ HttpRequest) {
  override def apply(httpRequest: HttpRequest): HttpRequest = {
    httpRequest.withUri(uri)
  }
}

case object ProxyHeaderRequestInterceptor extends ((HttpRequest) ⇒ HttpRequest) {
  override def apply(httpRequest: HttpRequest): HttpRequest = {
    val headers: Seq[HttpHeader] = httpRequest.headers
    val remoteAddressHeaderOption = headers.collectFirst({ case h: `Remote-Address` ⇒ h })

    val xRealIpPF: PartialFunction[HttpHeader, `X-Real-Ip`] = { case h: `X-Real-Ip` ⇒ h }
    val xForwardedForPF: PartialFunction[HttpHeader, `X-Forwarded-For`] = { case h: `X-Forwarded-For` ⇒ h }

    val xRealIpHeaderOption = headers.collectFirst(xRealIpPF)
    val xForwardedForHeaderOption = headers.collectFirst(xForwardedForPF)
    //TODO: Add `Via` HttpHeader as defined in https://tools.ietf.org/html/rfc2616#section-14.45 in akka.http.scaladsl.model.headers
    //var viaHeaderOption: Option[HttpHeader] = headers.collectFirst({case h: HttpHeader if "via".equalsIgnoreCase(h.name) => h})

    val remoteAddressOption = remoteAddressHeaderOption.map(_.address)
    val xRealIpOption = xRealIpHeaderOption.map(_.address)

    val ipOption = xRealIpOption.orElse(xForwardedForHeaderOption.flatMap(_.addresses.headOption).orElse(remoteAddressOption))

    val updatedXRealIpHeaderOption = ipOption.map(`X-Real-Ip`(_))
    val updatedXForwardedForHeaderOption = remoteAddressOption.map(remoteAddresss ⇒ xForwardedForHeaderOption.fold(`X-Forwarded-For`(remoteAddresss))(h ⇒ `X-Forwarded-For`(h.addresses ++ Seq(remoteAddresss))))

    val updatedHeaders = headers.filterNot((xRealIpPF orElse xForwardedForPF).isDefinedAt(_)) ++ (updatedXRealIpHeaderOption.toSeq ++ updatedXForwardedForHeaderOption.toSeq)
    httpRequest.withHeaders(updatedHeaders)

    //TODO: This has some security implications: What to do with cookies, authentication data, etc.?
  }
}

case class SingleRequestExecutor(implicit val system: ActorSystem, implicit val materializer: Materializer) extends ((HttpRequest) ⇒ Future[HttpResponse]) {
  override def apply(httpRequest: HttpRequest): Future[HttpResponse] = {
    Http().singleRequest(httpRequest)
  }
}

case class OutgoingRequestExecutor(implicit val system: ActorSystem, implicit val materializer: Materializer) extends ((HttpRequest) ⇒ Future[HttpResponse]) {
  override def apply(httpRequest: HttpRequest): Future[HttpResponse] = {
    implicit val executor = system.dispatcher
    val authority: Authority = httpRequest.uri.authority
    val source = Source.single(httpRequest)

    val flow: Flow[HttpRequest, HttpResponse, Future[OutgoingConnection]] = httpRequest.uri.scheme match {
      case "http" ⇒ Http().outgoingConnection(authority.host.toString, authority.port)
      case _      ⇒ Http().outgoingConnectionHttps(authority.host.toString, authority.port)
    }
    source.via(flow).toMat(Sink.head)(Keep.right).run()
  }
}

case class CachedHostConnectionPoolRequestExecutor(implicit val system: ActorSystem, implicit val materializer: Materializer) extends ((HttpRequest) ⇒ Future[HttpResponse]) {
  override def apply(httpRequest: HttpRequest): Future[HttpResponse] = {
    implicit val executor = system.dispatcher
    val authority: Authority = httpRequest.uri.authority
    val source = Source.single(httpRequest → 1)

    val flow: Flow[(HttpRequest, Int), (Try[HttpResponse], Int), HostConnectionPool] = httpRequest.uri.scheme match {
      case "http" ⇒ Http().cachedHostConnectionPool[Int](authority.host.toString, authority.port)
      case _      ⇒ Http().cachedHostConnectionPoolHttps[Int](authority.host.toString, authority.port)
    }
    source.via(flow).map(x ⇒ x._1).toMat(Sink.head)(Keep.right).run().flatMap({
      case Success(res) ⇒ Future.successful(res)
      case Failure(e)   ⇒ Future.failed(e)
    })
  }
}

