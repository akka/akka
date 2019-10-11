/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io

import akka.actor.Actor

/**
 * Where as it is possible to plug in alternative DNS implementations it is not recommended.
 *
 * It is expected that this will be deprecated/removed in future Akka versions
 *
 */
// TODO make private and remove deprecated in 2.7.0
@deprecated("Overriding the DNS implementation will be removed in future versions of Akka", "2.6.0")
trait DnsProvider {
  def cache: Dns = new SimpleDnsCache()
  def actorClass: Class[_ <: Actor]
  def managerClass: Class[_ <: Actor]
}
