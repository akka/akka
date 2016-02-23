/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.util

import org.scalatest.{ Matchers, WordSpec }

import scala.collection.immutable

object ReflectSpec {
  final class A
  final class B

  class One(a: A)
  class Two(a: A, b: B)

  class MultipleOne(a: A, b: B) {
    def this(a: A) { this(a, null) }
    def this(b: B) { this(null, b) }
  }
}

class ReflectSpec extends WordSpec with Matchers {

  import akka.util.ReflectSpec._

  "Reflect#findConstructor" must {

    "deal with simple 1 matching case" in {
      Reflect.findConstructor(classOf[One], immutable.Seq(new A))
    }
    "deal with 2-params 1 matching case" in {
      Reflect.findConstructor(classOf[Two], immutable.Seq(new A, new B))
    }
    "deal with 2-params where one is `null` 1 matching case" in {
      Reflect.findConstructor(classOf[Two], immutable.Seq(new A, null))
      Reflect.findConstructor(classOf[Two], immutable.Seq(null, new B))
    }
    "deal with `null` in 1 matching case" in {
      val constructor = Reflect.findConstructor(classOf[One], immutable.Seq(null))
      constructor.newInstance(null)
    }
    "deal with multiple constructors" in {
      Reflect.findConstructor(classOf[MultipleOne], immutable.Seq(new A))
      Reflect.findConstructor(classOf[MultipleOne], immutable.Seq(new B))
      Reflect.findConstructor(classOf[MultipleOne], immutable.Seq(new A, new B))
    }
    "throw when multiple matching constructors" in {
      intercept[IllegalArgumentException] {
        Reflect.findConstructor(classOf[MultipleOne], immutable.Seq(null))
      }
    }
  }

}
