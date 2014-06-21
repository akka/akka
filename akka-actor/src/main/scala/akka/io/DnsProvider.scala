package akka.io

import akka.actor.Actor

trait DnsProvider {
  def cache: Dns
  def actorClass: Class[_ <: Actor]
  def managerClass: Class[_ <: Actor]
}
