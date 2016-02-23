/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.server

import java.net.InetSocketAddress

import akka.stream.Attributes

/**
 * INTERNAL API
 * Internally used attributes set in the HTTP pipeline.
 * May potentially be opened up in the future.
 */
private[akka] object HttpAttributes {
  import Attributes._

  private[akka] final case class RemoteAddress(address: Option[InetSocketAddress]) extends Attribute

  private[akka] def remoteAddress(address: Option[InetSocketAddress]) =
    Attributes(RemoteAddress(address))

}
