/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import org.scalatest.WordSpec
import org.scalatest.Matchers
import java.net.URLEncoder
import scala.collection.immutable

class RelativeActorPathSpec extends WordSpec with Matchers {

  def elements(path: String): immutable.Seq[String] = RelativeActorPath.unapply(path).getOrElse(Nil)

  "RelativeActorPath" must {
    "match single name" in {
      elements("foo") should be(List("foo"))
    }
    "match path separated names" in {
      elements("foo/bar/baz") should be(List("foo", "bar", "baz"))
    }
    "match url encoded name" in {
      val name = URLEncoder.encode("akka://ClusterSystem@127.0.0.1:2552", "UTF-8")
      elements(name) should be(List(name))
    }
    "match path with uid fragment" in {
      elements("foo/bar/baz#1234") should be(List("foo", "bar", "baz#1234"))
    }
  }
}
