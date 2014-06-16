/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi

import akka.http.{ HttpExt, model }
import akka.actor.ActorSystem
import java.net.InetSocketAddress

/**
 *  INTERNAL API
 *
 *  Accessors for constructors with default arguments to be used from the Java implementation
 */
private[http] object Accessors {
  /** INTERNAL API */
  private[http] def HttpRequest(): HttpRequest = model.HttpRequest()
  /** INTERNAL API */
  private[http] def HttpResponse(): HttpResponse = model.HttpResponse()

  /** INTERNAL API */
  private[http] def Uri(uri: model.Uri): Uri = JavaUri(uri)
  /** INTERNAL API */
  private[http] def Bind(host: String, port: Int): AnyRef = akka.http.Http.Bind(host, port)
}
