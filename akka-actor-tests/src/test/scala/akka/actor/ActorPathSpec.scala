/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor

import java.net.MalformedURLException

import org.scalatest.{ Matchers, WordSpec }

class ActorPathSpec extends WordSpec with Matchers {

  "An ActorPath" must {

    "support parsing its String rep" in {
      val path = RootActorPath(Address("akka.tcp", "mysys")) / "user"
      ActorPath.fromString(path.toString) should ===(path)
    }

    "support parsing remote paths" in {
      val remote = "akka://my_sys@host:1234/some/ref"
      ActorPath.fromString(remote).toString should ===(remote)
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
      RootActorPath(a).toString should ===("akka.tcp://mysys/")
      (RootActorPath(a) / "user").toString should ===("akka.tcp://mysys/user")
      (RootActorPath(a) / "user" / "foo").toString should ===("akka.tcp://mysys/user/foo")
      (RootActorPath(a) / "user" / "foo" / "bar").toString should ===("akka.tcp://mysys/user/foo/bar")
    }

    "have correct path elements" in {
      (RootActorPath(Address("akka.tcp", "mysys")) / "user" / "foo" / "bar").elements.toSeq should ===(Seq("user", "foo", "bar"))
    }

    "create correct toStringWithoutAddress" in {
      val a = Address("akka.tcp", "mysys")
      RootActorPath(a).toStringWithoutAddress should ===("/")
      (RootActorPath(a) / "user").toStringWithoutAddress should ===("/user")
      (RootActorPath(a) / "user" / "foo").toStringWithoutAddress should ===("/user/foo")
      (RootActorPath(a) / "user" / "foo" / "bar").toStringWithoutAddress should ===("/user/foo/bar")
    }

    "validate path elements" in {
      intercept[InvalidActorNameException](ActorPath.validatePathElement("")).getMessage should include("must not be empty")
    }

    "create correct toStringWithAddress" in {
      val local = Address("akka.tcp", "mysys")
      val a = local.copy(host = Some("aaa"), port = Some(2552))
      val b = a.copy(host = Some("bb"))
      val c = a.copy(host = Some("cccc"))
      val root = RootActorPath(local)
      root.toStringWithAddress(a) should ===("akka.tcp://mysys@aaa:2552/")
      (root / "user").toStringWithAddress(a) should ===("akka.tcp://mysys@aaa:2552/user")
      (root / "user" / "foo").toStringWithAddress(a) should ===("akka.tcp://mysys@aaa:2552/user/foo")

      //      root.toStringWithAddress(b) should ===("akka.tcp://mysys@bb:2552/")
      (root / "user").toStringWithAddress(b) should ===("akka.tcp://mysys@bb:2552/user")
      (root / "user" / "foo").toStringWithAddress(b) should ===("akka.tcp://mysys@bb:2552/user/foo")

      root.toStringWithAddress(c) should ===("akka.tcp://mysys@cccc:2552/")
      (root / "user").toStringWithAddress(c) should ===("akka.tcp://mysys@cccc:2552/user")
      (root / "user" / "foo").toStringWithAddress(c) should ===("akka.tcp://mysys@cccc:2552/user/foo")

      val rootA = RootActorPath(a)
      rootA.toStringWithAddress(b) should ===("akka.tcp://mysys@aaa:2552/")
      (rootA / "user").toStringWithAddress(b) should ===("akka.tcp://mysys@aaa:2552/user")
      (rootA / "user" / "foo").toStringWithAddress(b) should ===("akka.tcp://mysys@aaa:2552/user/foo")
    }

    "not allow path separators in RootActorPath's name" in {
      intercept[IllegalArgumentException] {
        RootActorPath(Address("akka.tcp", "mysys"), "/user/boom/*") // illegally pass in a path where name is expected
      }.getMessage should include("is a path separator")

      // sanity check that creating such path still works
      ActorPath.fromString("akka://mysys/user/boom/*")
    }

  }
}
