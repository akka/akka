/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster

import org.scalatest.WordSpec
import org.scalatest.Matchers
import akka.actor.Address

class HeartbeatNodeRingPerfSpec extends WordSpec with Matchers {

  val nodesSize = sys.props.get("akka.cluster.HeartbeatNodeRingPerfSpec.nodesSize").getOrElse("250").toInt
  val iterations = sys.props.get("akka.cluster.HeartbeatNodeRingPerfSpec.iterations").getOrElse("10000").toInt

  def createHeartbeatNodeRingOfSize(size: Int): HeartbeatNodeRing = {
    val nodes = (1 to size).map(n ⇒ UniqueAddress(Address("akka.tcp", "sys", "node-" + n, 2552), n))
    val selfAddress = nodes(size / 2)
    HeartbeatNodeRing(selfAddress, nodes.toSet, Set.empty, 5)
  }

  val heartbeatNodeRing = createHeartbeatNodeRingOfSize(nodesSize)

  private def checkThunkForRing(ring: HeartbeatNodeRing, thunk: HeartbeatNodeRing ⇒ Unit, times: Int): Unit =
    for (i ← 1 to times) thunk(ring)

  private def myReceivers(ring: HeartbeatNodeRing): Unit = {
    val r = HeartbeatNodeRing(ring.selfAddress, ring.nodes, Set.empty, ring.monitoredByNrOfMembers)
    r.myReceivers.isEmpty should ===(false)
  }

  s"HeartbeatNodeRing of size $nodesSize" must {

    s"do a warm up run, $iterations times" in {
      checkThunkForRing(heartbeatNodeRing, myReceivers, iterations)
    }

    s"produce myReceivers, $iterations times" in {
      checkThunkForRing(heartbeatNodeRing, myReceivers, iterations)
    }

  }

}
