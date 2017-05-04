/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.server

import java.net.InetSocketAddress

import akka.annotation.InternalApi
import akka.stream.Attributes

/**
 * INTERNAL API
 * Internally used attributes set in the HTTP pipeline.
 * May potentially be opened up in the future.
 */
@InternalApi
private[akka] object HttpAttributes {
  import Attributes._

  private[akka] final case class RemoteAddress(address: InetSocketAddress) extends Attribute

  private[akka] def remoteAddress(address: InetSocketAddress) =
    Attributes(RemoteAddress(address))

  private[akka] val empty =
    Attributes()
}
