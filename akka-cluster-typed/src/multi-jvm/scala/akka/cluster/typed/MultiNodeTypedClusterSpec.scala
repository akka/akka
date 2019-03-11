/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.typed

import java.util.concurrent.ConcurrentHashMap

import akka.actor.{ Address, Scheduler }
import akka.actor.typed.ActorSystem
import akka.remote.testkit.{ FlightRecordingSupport, MultiNodeSpec, STMultiNodeSpec }
import akka.testkit.WatchedByCoroner
import org.scalatest.{ Matchers, Suite }
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.{ ClusterEvent, MemberStatus }
import akka.remote.testconductor.RoleName

import scala.concurrent.duration._
import scala.language.implicitConversions

trait MultiNodeTypedClusterSpec
    extends Suite
    with STMultiNodeSpec
    with WatchedByCoroner
    with FlightRecordingSupport
    with Matchers {
  self: MultiNodeSpec =>

  override def initialParticipants: Int = roles.size

  implicit def typedSystem: ActorSystem[Nothing] = system.toTyped
  implicit def scheduler: Scheduler = system.scheduler

  private val cachedAddresses = new ConcurrentHashMap[RoleName, Address]

  // TODO: Add support for typed to multi node test kit
  def cluster: Cluster = Cluster(system.toTyped)

  def clusterView: ClusterEvent.CurrentClusterState = cluster.state

  override def expectedTestDuration: FiniteDuration = 60.seconds

  /**
   * Lookup the Address for the role.
   *
   * Implicit conversion from RoleName to Address.
   *
   * It is cached, which has the implication that stopping
   * and then restarting a role (jvm) with another address is not
   * supported.
   */
  implicit def address(role: RoleName): Address = {
    cachedAddresses.get(role) match {
      case null =>
        val address = node(role).address
        cachedAddresses.put(role, address)
        address
      case address => address
    }
  }

  def formCluster(first: RoleName, rest: RoleName*): Unit = {
    runOn(first) {
      cluster.manager ! Join(cluster.selfMember.address)
      awaitAssert(cluster.state.members.exists { m =>
        m.uniqueAddress == cluster.selfMember.uniqueAddress && m.status == MemberStatus.Up
      } should be(true))
    }
    enterBarrier(first.name + "-joined")

    rest.foreach { node =>
      runOn(node) {
        cluster.manager ! Join(address(first))
        awaitAssert(cluster.state.members.exists { m =>
          m.uniqueAddress == cluster.selfMember.uniqueAddress && m.status == MemberStatus.Up
        } should be(true))
      }
    }
    enterBarrier("all-joined")
  }

}
