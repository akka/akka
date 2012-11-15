/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import java.net.URLEncoder
import scala.collection.immutable

class RelativeActorPathSpec extends WordSpec with MustMatchers {

  def elements(path: String): immutable.Seq[String] = RelativeActorPath.unapply(path).getOrElse(Nil)

  "RelativeActorPath" must {
    "match single name" in {
      elements("foo") must be(List("foo"))
    }
    "match path separated names" in {
      elements("foo/bar/baz") must be(List("foo", "bar", "baz"))
    }
    "match url encoded name" in {
      val name = URLEncoder.encode("akka://ClusterSystem@127.0.0.1:2552", "UTF-8")
      elements(name) must be(List(name))
    }
  }
}
