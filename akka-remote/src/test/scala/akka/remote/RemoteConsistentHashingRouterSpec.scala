/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote

import akka.testkit.AkkaSpec
import akka.actor.Address
import akka.routing.ActorSelectionRoutee
import akka.routing.ConsistentRoutee
import akka.routing.ConsistentHash

class RemoteConsistentHashingRouterSpec extends AkkaSpec("""
    akka.actor.provider = "akka.remote.RemoteActorRefProvider" """) {

  "ConsistentHashingGroup" must {

    "use same hash ring indepenent of self address" in {
      // simulating running router on two different nodes (a1, a2) with target routees on 3 other nodes (s1, s2, s3) 
      val a1 = Address("akka.tcp", "Sys", "client1", 2552)
      val a2 = Address("akka.tcp", "Sys", "client2", 2552)
      val s1 = ActorSelectionRoutee(system.actorSelection("akka.tcp://Sys@server1:2552/user/a/b"))
      val s2 = ActorSelectionRoutee(system.actorSelection("akka.tcp://Sys@server2:2552/user/a/b"))
      val s3 = ActorSelectionRoutee(system.actorSelection("akka.tcp://Sys@server3:2552/user/a/b"))
      val nodes1 = List(ConsistentRoutee(s1, a1), ConsistentRoutee(s2, a1), ConsistentRoutee(s3, a1))
      val nodes2 = List(ConsistentRoutee(s1, a2), ConsistentRoutee(s2, a2), ConsistentRoutee(s3, a2))
      val consistentHash1 = ConsistentHash(nodes1, 10)
      val consistentHash2 = ConsistentHash(nodes2, 10)
      val keys = List("A", "B", "C", "D", "E", "F", "G")
      val result1 = keys collect { case k ⇒ consistentHash1.nodeFor(k).routee }
      val result2 = keys collect { case k ⇒ consistentHash2.nodeFor(k).routee }
      result1 should ===(result2)
    }

  }

}
