/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns.internal

import akka.annotation.InternalApi
import akka.io._
import com.github.ghik.silencer.silent

/**
 * INTERNAL API
 */
@InternalApi
@silent("deprecated")
private[akka] class AsyncDnsProvider extends DnsProvider {
  override def cache: Dns = new SimpleDnsCache()
  override def actorClass = classOf[AsyncDnsResolver]
  override def managerClass = classOf[AsyncDnsManager]
}
