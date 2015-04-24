/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl

import java.net.InetSocketAddress
import scala.concurrent.Future

/**
 * Represents a prospective HTTP server binding.
 */
class ServerBinding private[http] (delegate: akka.http.scaladsl.Http.ServerBinding) {
  /**
   * The local address of the endpoint bound by the materialization of the `connections` [[Source]].
   */
  def localAddress: InetSocketAddress = delegate.localAddress

  /**
   * Asynchronously triggers the unbinding of the port that was bound by the materialization of the `connections`
   * [[Source]]
   *
   * The produced [[Future]] is fulfilled when the unbinding has been completed.
   */
  def unbind(): Future[Unit] = delegate.unbind()
}
