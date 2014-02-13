/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.datareplication

import org.scalatest.WordSpec
import org.scalatest.Matchers
import akka.actor.Address
import akka.cluster.UniqueAddress

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class TwoPhaseSetSpec extends WordSpec with Matchers {

  val user1 = """{"username":"john","password":"coltrane"}"""
  val user2 = """{"username":"sonny","password":"rollins"}"""
  val user3 = """{"username":"charlie","password":"parker"}"""
  val user4 = """{"username":"charles","password":"mingus"}"""

  "A TwoPhaseSet" must {

    "be able to add user" in {
      val c1 = TwoPhaseSet()

      val c2 = c1 :+ user1
      val c3 = c2 :+ user2

      val c4 = c3 :+ user4
      val c5 = c4 :+ user3

      c5.value should contain(user1)
      c5.value should contain(user2)
      c5.value should contain(user3)
      c5.value should contain(user4)
    }

    "be able to remove added user" in {
      val c1 = TwoPhaseSet()

      val c2 = c1 :+ user1
      val c3 = c2 :+ user2

      val c4 = c3 :- user2
      val c5 = c4 :- user1

      c5.value should not contain (user1)
      c5.value should not contain (user2)
    }

    "be throw exception if attempt to remove element that is not part of the set" in {
      val c1 = TwoPhaseSet()

      val c2 = c1 :+ user1
      val c3 = c2 :+ user2

      intercept[IllegalStateException] { c3 :- user3 }
    }

    "be throw exception if attempt to add an element previously removed from set" in {
      val c1 = TwoPhaseSet()

      val c2 = c1 :+ user1
      val c3 = c2 :- user1

      c3.value should not contain (user1)

      intercept[IllegalStateException] { c3 :+ user1 }
    }

    "be able to have its user set correctly merged with another TwoPhaseSet with unique user sets" in {
      // set 1
      val c11 = TwoPhaseSet()

      val c12 = c11 :+ user1
      val c13 = c12 :+ user2

      c13.value should contain(user1)
      c13.value should contain(user2)

      // set 2
      val c21 = TwoPhaseSet()

      val c22 = c21 :+ user3
      val c23 = c22 :+ user4
      val c24 = c23 :- user3

      c24.value should not contain (user3)
      c24.value should contain(user4)

      // merge both ways
      val merged1 = c13 merge c24
      merged1.value should contain(user1)
      merged1.value should contain(user2)
      merged1.value should not contain (user3)
      merged1.value should contain(user4)

      val merged2 = c24 merge c13
      merged2.value should contain(user1)
      merged2.value should contain(user2)
      merged2.value should not contain (user3)
      merged2.value should contain(user4)
    }

    "be able to have its user set correctly merged with another TwoPhaseSet with overlapping user sets" in {
      // set 1
      val c10 = TwoPhaseSet()

      val c11 = c10 :+ user1
      val c12 = c11 :+ user2
      val c13 = c12 :- user1

      c13.value should not contain (user1)
      c13.value should contain(user2)

      // set 2
      val c20 = TwoPhaseSet()

      val c21 = c20 :+ user1
      val c22 = c21 :+ user3
      val c23 = c22 :+ user4

      c23.value should contain(user1)
      c23.value should contain(user3)
      c23.value should contain(user4)

      // merge both ways
      val merged1 = c13 merge c23
      merged1.value should not contain (user1)
      merged1.value should contain(user2)
      merged1.value should contain(user3)
      merged1.value should contain(user4)

      val merged2 = c23 merge c13
      merged2.value should not contain (user1)
      merged2.value should contain(user2)
      merged2.value should contain(user3)
      merged2.value should contain(user4)
    }

  }
}
