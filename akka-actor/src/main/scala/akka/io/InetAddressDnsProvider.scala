/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io

import com.github.ghik.silencer.silent

import akka.annotation.InternalApi

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
