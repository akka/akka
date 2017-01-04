/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.compress

import akka.actor._

object CompressionTestUtils {

  def minimalRef(name: String)(implicit system: ActorSystem): ActorRef =
    new MinimalActorRef {
      override def provider: ActorRefProvider = system.asInstanceOf[ActorSystemImpl].provider
      override def path: ActorPath = RootActorPath(provider.getDefaultAddress) / name
    }

}
