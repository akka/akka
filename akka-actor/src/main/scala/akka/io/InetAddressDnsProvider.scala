/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io

import akka.annotation.InternalApi
import com.github.ghik.silencer.silent

/**
 * INTERNAL API
 */
@silent("deprecated")
@InternalApi
class InetAddressDnsProvider extends DnsProvider {
  override def cache: Dns = new SimpleDnsCache()
  override def actorClass = classOf[InetAddressDnsResolver]
  override def managerClass = classOf[SimpleDnsManager]
}
