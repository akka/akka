/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import scala.collection.immutable.HashSet
import scala.concurrent.duration.Deadline
import org.scalatest.WordSpec
import org.scalatest.matchers.ShouldMatchers
import akka.actor.Address

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class HeartbeatNodeRingPerfSpec extends WordSpec with ShouldMatchers {

  val nodesSize = sys.props.get("akka.cluster.HeartbeatNodeRingPerfSpec.nodesSize").getOrElse("250").toInt
  val iterations = sys.props.get("akka.cluster.HeartbeatNodeRingPerfSpec.iterations").getOrElse("10000").toInt

  def createHeartbeatNodeRingOfSize(size: Int): HeartbeatNodeRing = {
    val nodes = (1 to size).map(n ⇒ Address("akka.tcp", "sys", "node-" + n, 2552)).toSet
    val selfAddress = Address("akka.tcp", "sys", "node-" + (size / 2), 2552)
    HeartbeatNodeRing(selfAddress, nodes, 5)
  }

  def createClusterHeartbeatSenderStateOfSize(size: Int): ClusterHeartbeatSenderState = {
    val nodes = (1 to size).map(n ⇒ Address("akka.tcp", "sys", "node-" + n, 2552)).to[HashSet]
    val selfAddress = Address("akka.tcp", "sys", "node-" + (size / 2), 2552)
    ClusterHeartbeatSenderState.empty(selfAddress, 5).reset(nodes)
  }

  val heartbeatNodeRing = createHeartbeatNodeRingOfSize(nodesSize)
  val heartbeatSenderState = createClusterHeartbeatSenderStateOfSize(nodesSize)

  def checkThunkForRing(ring: HeartbeatNodeRing, thunk: HeartbeatNodeRing ⇒ Unit, times: Int): Unit =
    for (i ← 1 to times) thunk(ring)

  def checkThunkForState(state: ClusterHeartbeatSenderState, thunk: ClusterHeartbeatSenderState ⇒ Unit, times: Int): Unit =
    for (i ← 1 to times) thunk(state)

  def myReceivers(ring: HeartbeatNodeRing): Unit = {
    val r = HeartbeatNodeRing(ring.selfAddress, ring.nodes, ring.monitoredByNrOfMembers)
    r.myReceivers.isEmpty should be(false)
  }

  def mySenders(ring: HeartbeatNodeRing): Unit = {
    val r = HeartbeatNodeRing(ring.selfAddress, ring.nodes, ring.monitoredByNrOfMembers)
    r.mySenders.isEmpty should be(false)
  }

  def reset(state: ClusterHeartbeatSenderState): Unit = {
    val s = ClusterHeartbeatSenderState.empty(state.ring.selfAddress, state.ring.monitoredByNrOfMembers).reset(
      state.ring.nodes.asInstanceOf[HashSet[Address]])
    s.active.isEmpty should be(false)
  }

  def addMember(state: ClusterHeartbeatSenderState): Unit = {
    val s = state.addMember(Address("akka.tcp", "sys", "new-node", 2552))
    s.active.isEmpty should be(false)
  }

  def removeMember(state: ClusterHeartbeatSenderState): Unit = {
    val s = state.removeMember(Address("akka.tcp", "sys", "node-" + (nodesSize / 3), 2552))
    s.active.isEmpty should be(false)
  }

  def addHeartbeatRequest(state: ClusterHeartbeatSenderState): Unit = {
    val a = Address("akka.tcp", "sys", "node-" + (nodesSize / 3), 2552)
    val s = state.addHeartbeatRequest(a, Deadline.now)
    s.active should contain(a)
  }

  s"HeartbeatNodeRing of size $nodesSize" must {

    s"do a warm up run, $iterations times" in {
      checkThunkForRing(heartbeatNodeRing, myReceivers, iterations)
    }

    s"produce myReceivers, $iterations times" in {
      checkThunkForRing(heartbeatNodeRing, myReceivers, iterations)
    }

    s"produce mySenders, $iterations times" in {
      checkThunkForRing(heartbeatNodeRing, mySenders, iterations)
    }
  }

  s"ClusterHeartbeatSenderState of size $nodesSize" must {

    s"do a warm up run, $iterations times" in {
      checkThunkForState(heartbeatSenderState, reset, iterations)
    }

    s"reset, $iterations times" in {
      checkThunkForState(heartbeatSenderState, reset, iterations)
    }

    s"addMember node, $iterations times" in {
      checkThunkForState(heartbeatSenderState, addMember, iterations)
    }

    s"removeMember node, $iterations times" in {
      checkThunkForState(heartbeatSenderState, removeMember, iterations)
    }

    s"addHeartbeatRequest node, $iterations times" in {
      checkThunkForState(heartbeatSenderState, addHeartbeatRequest, iterations)
    }

  }
}
