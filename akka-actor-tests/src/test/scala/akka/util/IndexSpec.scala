/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.util

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

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
      assert(index.valueIterator("s1").toSet === Set(1))
    }
    "take and return several values" in {
      val index = emptyIndex
      assert(index.put("s1", 1))
      assert(!index.put("s1", 1))
      index.put("s1", 2)
      index.put("s1", 3)
      index.put("s2", 4)
      assert(index.valueIterator("s1").toSet === Set(1, 2, 3))
      assert(index.valueIterator("s2").toSet === Set(4))
    }
    "remove values" in {
      val index = emptyIndex
      index.put("s1", 1)
      index.put("s1", 2)
      index.put("s2", 1)
      index.put("s2", 2)
      //Remove value
      assert(index.remove("s1", 1))
      assert(index.remove("s1", 1) === false)
      assert(index.valueIterator("s1").toSet === Set(2))
      //Remove key
      index.remove("s2") match {
        case Some(iter) ⇒ assert(iter.toSet === Set(1, 2))
        case None       ⇒ fail()
      }
      assert(index.remove("s2") === None)
      assert(index.valueIterator("s2").toSet === Set())
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
      assert(index.valueIterator("s1").toSet === Set(2, 3))
      assert(index.valueIterator("s2").toSet === Set(2))
      assert(index.valueIterator("s3").toSet === Set(2))
    }
    "apply a function for all key-value pairs and find every value" in {
      val index = indexWithValues

      var valueCount = 0
      index.foreach((key, value) ⇒ {
        valueCount = valueCount + 1
        assert(index.findValue(key)(_ == value) === Some(value))
      })
      assert(valueCount === 6)
    }
    "be cleared" in {
      val index = indexWithValues
      index.clear()
      assert(index.isEmpty)
    }
  }
}