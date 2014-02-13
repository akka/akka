/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.datareplication

import org.scalatest.WordSpec
import org.scalatest.Matchers
import akka.cluster.UniqueAddress
import akka.actor.Address

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ORSetSpec extends WordSpec with Matchers {

  val node1 = UniqueAddress(Address("akka.tcp", "Sys", "localhost", 2551), 1)
  val node2 = UniqueAddress(node1.address.copy(port = Some(2552)), 2)

  val user1 = """{"username":"john","password":"coltrane"}"""
  val user2 = """{"username":"sonny","password":"rollins"}"""
  val user3 = """{"username":"charlie","password":"parker"}"""
  val user4 = """{"username":"charles","password":"mingus"}"""

  "A ORSet" must {

    "be able to add user" in {
      val c1 = ORSet()

      val c2 = c1.add(node1, user1)
      val c3 = c2.add(node1, user2)

      val c4 = c3.add(node1, user4)
      val c5 = c4.add(node1, user3)

      c5.value should contain(user1)
      c5.value should contain(user2)
      c5.value should contain(user3)
      c5.value should contain(user4)
    }

    "be able to remove added user" in {
      val c1 = ORSet()

      val c2 = c1.add(node1, user1)
      val c3 = c2.add(node1, user2)

      val c4 = c3.remove(node1, user2)
      val c5 = c4.remove(node1, user1)

      c5.value should not contain (user1)
      c5.value should not contain (user2)
    }

    "be able to add removed" in {
      val c1 = ORSet()
      val c2 = c1.remove(node1, user1)
      val c3 = c2.add(node1, user1)
      c3.value should contain(user1)
      val c4 = c3.remove(node1, user1)
      c4.value should not contain (user1)
      val c5 = c4.add(node1, user1)
      c5.value should contain(user1)
    }

    "be able to remove and add several times" in {
      val c1 = ORSet()

      val c2 = c1.add(node1, user1)
      val c3 = c2.add(node1, user2)
      val c4 = c3.remove(node1, user1)
      c4.value should not contain (user1)
      c4.value should contain(user2)

      val c5 = c4.add(node1, user1)
      val c6 = c5.add(node1, user2)
      c6.value should contain(user1)
      c6.value should contain(user2)

      val c7 = c6.remove(node1, user1)
      val c8 = c7.add(node1, user2)
      val c9 = c8.remove(node1, user1)
      c9.value should not contain (user1)
      c9.value should contain(user2)
    }

    "be able to have its user set correctly merged with another ORSet with unique user sets" in {
      // set 1
      val c1 = ORSet().add(node1, user1).add(node1, user2)
      c1.value should contain(user1)
      c1.value should contain(user2)

      // set 2
      val c2 = ORSet().add(node2, user3).add(node2, user4).remove(node2, user3)

      c2.value should not contain (user3)
      c2.value should contain(user4)

      // merge both ways
      val merged1 = c1 merge c2
      merged1.value should contain(user1)
      merged1.value should contain(user2)
      merged1.value should not contain (user3)
      merged1.value should contain(user4)

      val merged2 = c2 merge c1
      merged2.value should contain(user1)
      merged2.value should contain(user2)
      merged2.value should not contain (user3)
      merged2.value should contain(user4)
    }

    "be able to have its user set correctly merged with another ORSet with overlapping user sets" in {
      // set 1
      val c1 = ORSet().add(node1, user1).add(node1, user2).add(node1, user3).remove(node1, user1).remove(node1, user3)

      c1.value should not contain (user1)
      c1.value should contain(user2)
      c1.value should not contain (user3)

      // set 2
      val c2 = ORSet().add(node2, user1).add(node2, user2).add(node2, user3).add(node2, user4).remove(node2, user3)

      c2.value should contain(user1)
      c2.value should contain(user2)
      c2.value should not contain (user3)
      c2.value should contain(user4)

      // merge both ways
      val merged1 = c1 merge c2
      merged1.value should contain(user1)
      merged1.value should contain(user2)
      merged1.value should not contain (user3)
      merged1.value should contain(user4)

      val merged2 = c2 merge c1
      merged2.value should contain(user1)
      merged2.value should contain(user2)
      merged2.value should not contain (user3)
      merged2.value should contain(user4)
    }

    "be able to have its user set correctly merged for concurrent updates" in {
      val c1 = ORSet().add(node1, user1).add(node1, user2).add(node1, user3)

      c1.value should contain(user1)
      c1.value should contain(user2)
      c1.value should contain(user3)

      val c2 = c1.add(node2, user1).remove(node2, user2).remove(node2, user3)

      c2.value should contain(user1)
      c2.value should not contain (user2)
      c2.value should not contain (user3)

      // merge both ways
      val merged1 = c1 merge c2
      merged1.value should contain(user1)
      merged1.value should not contain (user2)
      merged1.value should not contain (user3)

      val merged2 = c2 merge c1
      merged2.value should contain(user1)
      merged2.value should not contain (user2)
      merged2.value should not contain (user3)

      val c3 = c1.add(node1, user4).remove(node1, user3).add(node1, user2)

      // merge both ways
      val merged3 = c2 merge c3
      merged3.value should contain(user1)
      merged3.value should contain(user2)
      merged3.value should not contain (user3)
      merged3.value should contain(user4)

      val merged4 = c3 merge c2
      merged4.value should contain(user1)
      merged4.value should contain(user2)
      merged4.value should not contain (user3)
      merged4.value should contain(user4)
    }

  }
}
