/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io

import akka.actor.Actor

trait DnsProvider {
  def cache: Dns
  def actorClass: Class[_ <: Actor]
  def managerClass: Class[_ <: Actor]
}
