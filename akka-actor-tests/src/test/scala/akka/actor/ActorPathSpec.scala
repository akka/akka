/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import java.net.MalformedURLException

import org.scalatest.Matchers
import org.scalatest.WordSpec

class ActorPathSpec extends WordSpec with Matchers {

  "An ActorPath" must {

    "support parsing its String rep" in {
      val path = RootActorPath(Address("akka.tcp", "mysys")) / "user"
      ActorPath.fromString(path.toString) should be(path)
    }

    "support parsing remote paths" in {
      val remote = "akka://my_sys@host:1234/some/ref"
      ActorPath.fromString(remote).toString should be(remote)
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
      RootActorPath(a).toString should be("akka.tcp://mysys/")
      (RootActorPath(a) / "user").toString should be("akka.tcp://mysys/user")
      (RootActorPath(a) / "user" / "foo").toString should be("akka.tcp://mysys/user/foo")
      (RootActorPath(a) / "user" / "foo" / "bar").toString should be("akka.tcp://mysys/user/foo/bar")
    }

    "have correct path elements" in {
      (RootActorPath(Address("akka.tcp", "mysys")) / "user" / "foo" / "bar").elements.toSeq should be(Seq("user", "foo", "bar"))
    }

    "create correct toStringWithoutAddress" in {
      val a = Address("akka.tcp", "mysys")
      RootActorPath(a).toStringWithoutAddress should be("/")
      (RootActorPath(a) / "user").toStringWithoutAddress should be("/user")
      (RootActorPath(a) / "user" / "foo").toStringWithoutAddress should be("/user/foo")
      (RootActorPath(a) / "user" / "foo" / "bar").toStringWithoutAddress should be("/user/foo/bar")
    }

    "create correct toStringWithAddress" in {
      val local = Address("akka.tcp", "mysys")
      val a = local.copy(host = Some("aaa"), port = Some(2552))
      val b = a.copy(host = Some("bb"))
      val c = a.copy(host = Some("cccc"))
      val root = RootActorPath(local)
      root.toStringWithAddress(a) should be("akka.tcp://mysys@aaa:2552/")
      (root / "user").toStringWithAddress(a) should be("akka.tcp://mysys@aaa:2552/user")
      (root / "user" / "foo").toStringWithAddress(a) should be("akka.tcp://mysys@aaa:2552/user/foo")

      //      root.toStringWithAddress(b) should be("akka.tcp://mysys@bb:2552/")
      (root / "user").toStringWithAddress(b) should be("akka.tcp://mysys@bb:2552/user")
      (root / "user" / "foo").toStringWithAddress(b) should be("akka.tcp://mysys@bb:2552/user/foo")

      root.toStringWithAddress(c) should be("akka.tcp://mysys@cccc:2552/")
      (root / "user").toStringWithAddress(c) should be("akka.tcp://mysys@cccc:2552/user")
      (root / "user" / "foo").toStringWithAddress(c) should be("akka.tcp://mysys@cccc:2552/user/foo")

      val rootA = RootActorPath(a)
      rootA.toStringWithAddress(b) should be("akka.tcp://mysys@aaa:2552/")
      (rootA / "user").toStringWithAddress(b) should be("akka.tcp://mysys@aaa:2552/user")
      (rootA / "user" / "foo").toStringWithAddress(b) should be("akka.tcp://mysys@aaa:2552/user/foo")
    }
  }
}
