/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.datareplication

import org.scalatest.WordSpec
import org.scalatest.Matchers

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class GSetSpec extends WordSpec with Matchers {

  val user1 = """{"username":"john","password":"coltrane"}"""
  val user2 = """{"username":"sonny","password":"rollins"}"""
  val user3 = """{"username":"charlie","password":"parker"}"""
  val user4 = """{"username":"charles","password":"mingus"}"""

  "A GSet" must {

    "be able to add user" in {
      val c1 = GSet()

      val c2 = c1 :+ user1
      val c3 = c2 :+ user2

      val c4 = c3 :+ user4
      val c5 = c4 :+ user3

      c5.value should contain(user1)
      c5.value should contain(user2)
      c5.value should contain(user3)
      c5.value should contain(user4)
    }

    "be able to have its user set correctly merged with another GSet with unique user sets" in {
      // set 1
      val c11 = GSet()

      val c12 = c11 :+ user1
      val c13 = c12 :+ user2

      c13.value should contain(user1)
      c13.value should contain(user2)

      // set 2
      val c21 = GSet()

      val c22 = c21 :+ user3
      val c23 = c22 :+ user4

      c23.value should contain(user3)
      c23.value should contain(user4)

      // merge both ways
      val merged1 = c13 merge c23
      merged1.value should contain(user1)
      merged1.value should contain(user2)
      merged1.value should contain(user3)
      merged1.value should contain(user4)

      val merged2 = c23 merge c13
      merged2.value should contain(user1)
      merged2.value should contain(user2)
      merged2.value should contain(user3)
      merged2.value should contain(user4)
    }

    "be able to have its user set correctly merged with another GSet with overlapping user sets" in {
      // set 1
      val c10 = GSet()

      val c11 = c10 :+ user1
      val c12 = c11 :+ user2
      val c13 = c12 :+ user3

      c13.value should contain(user1)
      c13.value should contain(user2)
      c13.value should contain(user3)

      // set 2
      val c20 = GSet()

      val c21 = c20 :+ user2
      val c22 = c21 :+ user3
      val c23 = c22 :+ user4

      c23.value should contain(user2)
      c23.value should contain(user3)
      c23.value should contain(user4)

      // merge both ways
      val merged1 = c13 merge c23
      merged1.value should contain(user1)
      merged1.value should contain(user2)
      merged1.value should contain(user3)
      merged1.value should contain(user4)

      val merged2 = c23 merge c13
      merged2.value should contain(user1)
      merged2.value should contain(user2)
      merged2.value should contain(user3)
      merged2.value should contain(user4)
    }

  }
}
