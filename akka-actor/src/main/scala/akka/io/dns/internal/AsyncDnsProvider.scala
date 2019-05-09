/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns.internal

import akka.annotation.InternalApi
import akka.io._

/**
 * INTERNAL API
 */
@InternalApi
private[akka] class AsyncDnsProvider extends DnsProvider {
  override def cache: Dns = new AsyncDnsCache()
  override def actorClass = classOf[AsyncDnsResolver]
  override def managerClass = classOf[AsyncDnsManager]
}
