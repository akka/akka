/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl

import java.net.InetSocketAddress
import java.util.concurrent.CompletionStage
import scala.compat.java8.FutureConverters._

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
   * The produced [[java.util.concurrent.CompletionStage]] is fulfilled when the unbinding has been completed.
   */
  def unbind(): CompletionStage[Unit] = delegate.unbind().toJava
}
