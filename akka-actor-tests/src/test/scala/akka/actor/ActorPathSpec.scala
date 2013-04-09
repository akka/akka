/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import java.net.MalformedURLException

class ActorPathSpec extends WordSpec with MustMatchers {

  "An ActorPath" must {

    "support parsing its String rep" in {
      val path = RootActorPath(Address("akka.tcp", "mysys")) / "user"
      ActorPath.fromString(path.toString) must be(path)
    }

    "support parsing remote paths" in {
      val remote = "akka://sys@host:1234/some/ref"
      ActorPath.fromString(remote).toString must be(remote)
    }

    "throw exception upon malformed paths" in {
      intercept[MalformedURLException] { ActorPath.fromString("") }
      intercept[MalformedURLException] { ActorPath.fromString("://hallo") }
      intercept[MalformedURLException] { ActorPath.fromString("s://dd@:12") }
      intercept[MalformedURLException] { ActorPath.fromString("s://dd@h:hd") }
      intercept[MalformedURLException] { ActorPath.fromString("a://l:1/b") }
    }

    "create correct toString" in {
      val a = Address("akka.tcp", "mysys")
      RootActorPath(a).toString must be("akka.tcp://mysys/")
      (RootActorPath(a) / "user").toString must be("akka.tcp://mysys/user")
      (RootActorPath(a) / "user" / "foo").toString must be("akka.tcp://mysys/user/foo")
      (RootActorPath(a) / "user" / "foo" / "bar").toString must be("akka.tcp://mysys/user/foo/bar")
    }

    "have correct path elements" in {
      (RootActorPath(Address("akka.tcp", "mysys")) / "user" / "foo" / "bar").elements.toSeq must be(Seq("user", "foo", "bar"))
    }

    "create correct toStringWithAddress" in {
      val local = Address("akka.tcp", "mysys")
      val a = local.copy(host = Some("aaa"), port = Some(2552))
      val b = a.copy(host = Some("bb"))
      val c = a.copy(host = Some("cccc"))
      val root = RootActorPath(local)
      root.toStringWithAddress(a) must be("akka.tcp://mysys@aaa:2552/")
      (root / "user").toStringWithAddress(a) must be("akka.tcp://mysys@aaa:2552/user")
      (root / "user" / "foo").toStringWithAddress(a) must be("akka.tcp://mysys@aaa:2552/user/foo")

      //      root.toStringWithAddress(b) must be("akka.tcp://mysys@bb:2552/")
      (root / "user").toStringWithAddress(b) must be("akka.tcp://mysys@bb:2552/user")
      (root / "user" / "foo").toStringWithAddress(b) must be("akka.tcp://mysys@bb:2552/user/foo")

      root.toStringWithAddress(c) must be("akka.tcp://mysys@cccc:2552/")
      (root / "user").toStringWithAddress(c) must be("akka.tcp://mysys@cccc:2552/user")
      (root / "user" / "foo").toStringWithAddress(c) must be("akka.tcp://mysys@cccc:2552/user/foo")

      val rootA = RootActorPath(a)
      rootA.toStringWithAddress(b) must be("akka.tcp://mysys@aaa:2552/")
      (rootA / "user").toStringWithAddress(b) must be("akka.tcp://mysys@aaa:2552/user")
      (rootA / "user" / "foo").toStringWithAddress(b) must be("akka.tcp://mysys@aaa:2552/user/foo")

    }
  }
}
