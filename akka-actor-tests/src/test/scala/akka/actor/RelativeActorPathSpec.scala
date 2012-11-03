/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import java.net.URLEncoder

class RelativeActorPathSpec extends WordSpec with MustMatchers {

  def elements(path: String): Seq[String] = path match {
    case RelativeActorPath(elem) ⇒ elem.toSeq
    case _                       ⇒ Nil
  }

  "RelativeActorPath" must {
    "match single name" in {
      elements("foo") must be(Seq("foo"))
    }
    "match path separated names" in {
      elements("foo/bar/baz") must be(Seq("foo", "bar", "baz"))
    }
    "match url encoded name" in {
      val name = URLEncoder.encode("akka://ClusterSystem@127.0.0.1:2552", "UTF-8")
      elements(name) must be(Seq(name))
    }
  }
}
