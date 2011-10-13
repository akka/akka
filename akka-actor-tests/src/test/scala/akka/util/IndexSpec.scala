/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.util

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import scala.actors.Actor._
import java.util.concurrent.CountDownLatch

class IndexSpec extends WordSpec with MustMatchers {

  private def emptyIndex = new Index[String, Int](100, (a, b) ⇒ a.compareTo(b))

  private def indexWithValues = {
    val index = emptyIndex
    index.put("s1", 1)
    index.put("s1", 2)
    index.put("s1", 3)
    index.put("s2", 1)
    index.put("s2", 2)
    index.put("s3", 2)

    index
  }

  "An Index" must {

    "take and return a value" in {
      val index = emptyIndex
      index.put("s1", 1)
      index.valueIterator("s1").toSet must be === Set(1)
    }
    "take and return several values" in {
      val index = emptyIndex
      index.put("s1", 1) must be === true
      index.put("s1", 1) must be === false
      index.put("s1", 2)
      index.put("s1", 3)
      index.put("s2", 4)
      index.valueIterator("s1").toSet must be === Set(1, 2, 3)
      index.valueIterator("s2").toSet must be === Set(4)
    }
    "remove values" in {
      val index = emptyIndex
      index.put("s1", 1)
      index.put("s1", 2)
      index.put("s2", 1)
      index.put("s2", 2)
      //Remove value
      index.remove("s1", 1) must be === true
      index.remove("s1", 1) must be === false
      index.valueIterator("s1").toSet must be === Set(2)
      //Remove key
      index.remove("s2") match {
        case Some(iter) ⇒ iter.toSet must be === Set(1, 2)
        case None       ⇒ fail()
      }
      index.remove("s2") must be === None
      index.valueIterator("s2").toSet must be === Set()
    }
    "remove the specified value" in {
      val index = emptyIndex
      index.put("s1", 1)
      index.put("s1", 2)
      index.put("s1", 3)
      index.put("s2", 1)
      index.put("s2", 2)
      index.put("s3", 2)

      index.removeValue(1)
      index.valueIterator("s1").toSet must be === Set(2, 3)
      index.valueIterator("s2").toSet must be === Set(2)
      index.valueIterator("s3").toSet must be === Set(2)
    }
    "apply a function for all key-value pairs and find every value" in {
      val index = indexWithValues

      var valueCount = 0
      index.foreach((key, value) ⇒ {
        valueCount = valueCount + 1
        index.findValue(key)(_ == value) must be === Some(value)
      })
      valueCount must be === 6
    }
    "be cleared" in {
      val index = indexWithValues
      index.isEmpty must be === false
      index.clear()
      index.isEmpty must be === true
    }
    "be able to be accessed in parallel" in {
      val index = new Index[Int, Int](100, (a, b) ⇒ a.compareTo(b))
      val iterations = 500
      val latch = new CountDownLatch(List(100, 50, 2, 100).map(_ * iterations).fold(0)(_ + _))
      //Fill
      for (key ← 1 to 10; value ← 1 to 10)
        index.put(key, value)
      //Perform operations in parallel
      for (_ ← 1 to iterations) {
        //Put actors
        actor {
          for (key ← 1 to 10; value ← 1 to 10)
            actor {
              index.put(key, value)
              latch.countDown() //100
            }
        }
        //Remove actors
        actor {
          for (key ← 1 to 10; value ← 1 to 5)
            actor {
              index.remove(key, value)
              latch.countDown() //50
            }
          for (key ← 9 to 10)
            actor {
              index.remove(key)
              latch.countDown() //2
            }
        }
        //Read actors
        actor {
          for (key ← 1 to 10; value ← 1 to 10)
            actor {
              val values = index.valueIterator(key)
              if (key < 9 && value > 5)
                (values contains value) must be === true
              latch.countDown() //100
            }
        }
      }
      latch.await()
    }
  }
}