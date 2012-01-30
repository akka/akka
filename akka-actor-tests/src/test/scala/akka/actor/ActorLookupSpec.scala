/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import akka.testkit._
import akka.util.duration._
import akka.dispatch.Await
import akka.pattern.ask
import java.net.MalformedURLException

object ActorLookupSpec {

  case class Create(child: String)

  trait Query
  case class LookupElems(path: Iterable[String]) extends Query
  case class LookupString(path: String) extends Query
  case class LookupPath(path: ActorPath) extends Query
  case class GetSender(to: ActorRef) extends Query

  val p = Props[Node]

  class Node extends Actor {
    def receive = {
      case Create(name)       ⇒ sender ! context.actorOf(p, name)
      case LookupElems(path)  ⇒ sender ! context.actorFor(path)
      case LookupString(path) ⇒ sender ! context.actorFor(path)
      case LookupPath(path)   ⇒ sender ! context.actorFor(path)
      case GetSender(ref)     ⇒ ref ! sender
    }
  }

}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ActorLookupSpec extends AkkaSpec with DefaultTimeout {
  import ActorLookupSpec._

  val c1 = system.actorOf(p, "c1")
  val c2 = system.actorOf(p, "c2")
  val c21 = Await.result((c2 ? Create("c21")).mapTo[ActorRef], timeout.duration)

  val sysImpl = system.asInstanceOf[ActorSystemImpl]

  val user = sysImpl.guardian
  val syst = sysImpl.systemGuardian
  val root = sysImpl.lookupRoot

  def empty(path: String) =
    new EmptyLocalActorRef(sysImpl.provider, path match {
      case RelativeActorPath(elems) ⇒ system.actorFor("/").path / elems
    }, system.eventStream)

  "An ActorSystem" must {

    "find actors by looking up their path" in {
      system.actorFor(c1.path) must be === c1
      system.actorFor(c2.path) must be === c2
      system.actorFor(c21.path) must be === c21
      system.actorFor(system / "c1") must be === c1
      system.actorFor(system / "c2") must be === c2
      system.actorFor(system / "c2" / "c21") must be === c21
      system.actorFor(system child "c2" child "c21") must be === c21 // test Java API
      system.actorFor(system / Seq("c2", "c21")) must be === c21

      import scala.collection.JavaConverters._
      system.actorFor(system descendant Seq("c2", "c21").asJava) // test Java API
    }

    "find actors by looking up their string representation" in {
      system.actorFor(c1.path.toString) must be === c1
      system.actorFor(c2.path.toString) must be === c2
      system.actorFor(c21.path.toString) must be === c21
    }

    "find actors by looking up their root-anchored relative path" in {
      system.actorFor(c1.path.elements.mkString("/", "/", "")) must be === c1
      system.actorFor(c2.path.elements.mkString("/", "/", "")) must be === c2
      system.actorFor(c21.path.elements.mkString("/", "/", "")) must be === c21
    }

    "find actors by looking up their relative path" in {
      system.actorFor(c1.path.elements.mkString("/")) must be === c1
      system.actorFor(c2.path.elements.mkString("/")) must be === c2
      system.actorFor(c21.path.elements.mkString("/")) must be === c21
    }

    "find actors by looking up their path elements" in {
      system.actorFor(c1.path.elements) must be === c1
      system.actorFor(c2.path.elements) must be === c2
      system.actorFor(c21.path.getElements) must be === c21 // test Java API
    }

    "find system-generated actors" in {
      system.actorFor("/user") must be === user
      system.actorFor("/deadLetters") must be === system.deadLetters
      system.actorFor("/system") must be === syst
      system.actorFor(syst.path) must be === syst
      system.actorFor(syst.path.toString) must be === syst
      system.actorFor("/") must be === root
      system.actorFor("..") must be === root
      system.actorFor(root.path) must be === root
      system.actorFor(root.path.toString) must be === root
      system.actorFor("user") must be === user
      system.actorFor("deadLetters") must be === system.deadLetters
      system.actorFor("system") must be === syst
      system.actorFor("user/") must be === user
      system.actorFor("deadLetters/") must be === system.deadLetters
      system.actorFor("system/") must be === syst
    }

    "return deadLetters or EmptyLocalActorRef, respectively, for non-existing paths" in {
      def check(lookup: ActorRef, result: ActorRef) = {
        lookup.getClass must be === result.getClass
        lookup must be === result
      }
      check(system.actorFor("a/b/c"), empty("a/b/c"))
      check(system.actorFor(""), system.deadLetters)
      check(system.actorFor("akka://all-systems/Nobody"), system.deadLetters)
      check(system.actorFor("akka://all-systems/user"), system.deadLetters)
      check(system.actorFor(system / "hallo"), empty("user/hallo"))
      check(system.actorFor(Seq()), system.deadLetters)
      check(system.actorFor(Seq("a")), empty("a"))
    }

    "find temporary actors" in {
      val f = c1 ? GetSender(testActor)
      val a = expectMsgType[ActorRef]
      a.path.elements.head must be === "temp"
      system.actorFor(a.path) must be === a
      system.actorFor(a.path.toString) must be === a
      system.actorFor(a.path.elements) must be === a
      system.actorFor(a.path.toString + "/") must be === a
      system.actorFor(a.path.toString + "/hallo").isTerminated must be === true
      f.isCompleted must be === false
      a.isTerminated must be === false
      a ! 42
      f.isCompleted must be === true
      Await.result(f, timeout.duration) must be === 42
      // clean-up is run as onComplete callback, i.e. dispatched on another thread
      awaitCond(system.actorFor(a.path).isTerminated, 1 second)
    }

  }

  "An ActorContext" must {

    val all = Seq(c1, c2, c21)

    "find actors by looking up their path" in {
      def check(looker: ActorRef, pathOf: ActorRef, result: ActorRef) {
        Await.result(looker ? LookupPath(pathOf.path), timeout.duration) must be === result
      }
      for {
        looker ← all
        target ← all
      } check(looker, target, target)
    }

    "find actors by looking up their string representation" in {
      def check(looker: ActorRef, pathOf: ActorRef, result: ActorRef) {
        Await.result(looker ? LookupString(pathOf.path.toString), timeout.duration) must be === result
        Await.result(looker ? LookupString(pathOf.path.toString + "/"), timeout.duration) must be === result
      }
      for {
        looker ← all
        target ← all
      } check(looker, target, target)
    }

    "find actors by looking up their root-anchored relative path" in {
      def check(looker: ActorRef, pathOf: ActorRef, result: ActorRef) {
        Await.result(looker ? LookupString(pathOf.path.elements.mkString("/", "/", "")), timeout.duration) must be === result
        Await.result(looker ? LookupString(pathOf.path.elements.mkString("/", "/", "/")), timeout.duration) must be === result
      }
      for {
        looker ← all
        target ← all
      } check(looker, target, target)
    }

    "find actors by looking up their relative path" in {
      def check(looker: ActorRef, result: ActorRef, elems: String*) {
        Await.result(looker ? LookupElems(elems), timeout.duration) must be === result
        Await.result(looker ? LookupString(elems mkString "/"), timeout.duration) must be === result
        Await.result(looker ? LookupString(elems mkString ("", "/", "/")), timeout.duration) must be === result
      }
      check(c1, user, "..")
      for {
        looker ← Seq(c1, c2)
        target ← all
      } check(looker, target, Seq("..") ++ target.path.elements.drop(1): _*)
      check(c21, user, "..", "..")
      check(c21, root, "..", "..", "..")
      check(c21, root, "..", "..", "..", "..")
    }

    "find system-generated actors" in {
      def check(target: ActorRef) {
        for (looker ← all) {
          Await.result(looker ? LookupPath(target.path), timeout.duration) must be === target
          Await.result(looker ? LookupString(target.path.toString), timeout.duration) must be === target
          Await.result(looker ? LookupString(target.path.toString + "/"), timeout.duration) must be === target
          Await.result(looker ? LookupString(target.path.elements.mkString("/", "/", "")), timeout.duration) must be === target
          if (target != root) Await.result(looker ? LookupString(target.path.elements.mkString("/", "/", "/")), timeout.duration) must be === target
        }
      }
      for (target ← Seq(root, syst, user, system.deadLetters)) check(target)
    }

    "return deadLetters or EmptyLocalActorRef, respectively, for non-existing paths" in {
      import scala.collection.JavaConverters._

      def checkOne(looker: ActorRef, query: Query, result: ActorRef) {
        val lookup = Await.result(looker ? query, timeout.duration)
        lookup.getClass must be === result.getClass
        lookup must be === result
      }
      def check(looker: ActorRef) {
        val lookname = looker.path.elements.mkString("", "/", "/")
        for (
          (l, r) ← Seq(
            LookupString("a/b/c") -> empty(lookname + "a/b/c"),
            LookupString("") -> system.deadLetters,
            LookupString("akka://all-systems/Nobody") -> system.deadLetters,
            LookupPath(system / "hallo") -> empty("user/hallo"),
            LookupPath(looker.path child "hallo") -> empty(lookname + "hallo"), // test Java API
            LookupPath(looker.path descendant Seq("a", "b").asJava) -> empty(lookname + "a/b"), // test Java API
            LookupElems(Seq()) -> system.deadLetters,
            LookupElems(Seq("a")) -> empty(lookname + "a"))
        ) checkOne(looker, l, r)
      }
      for (looker ← all) check(looker)
    }

    "find temporary actors" in {
      val f = c1 ? GetSender(testActor)
      val a = expectMsgType[ActorRef]
      a.path.elements.head must be === "temp"
      Await.result(c2 ? LookupPath(a.path), timeout.duration) must be === a
      Await.result(c2 ? LookupString(a.path.toString), timeout.duration) must be === a
      Await.result(c2 ? LookupString(a.path.elements.mkString("/", "/", "")), timeout.duration) must be === a
      Await.result(c2 ? LookupString("../../" + a.path.elements.mkString("/")), timeout.duration) must be === a
      Await.result(c2 ? LookupString(a.path.toString + "/"), timeout.duration) must be === a
      Await.result(c2 ? LookupString(a.path.elements.mkString("/", "/", "") + "/"), timeout.duration) must be === a
      Await.result(c2 ? LookupString("../../" + a.path.elements.mkString("/") + "/"), timeout.duration) must be === a
      Await.result(c2 ? LookupElems(Seq("..", "..") ++ a.path.elements), timeout.duration) must be === a
      Await.result(c2 ? LookupElems(Seq("..", "..") ++ a.path.elements :+ ""), timeout.duration) must be === a
      f.isCompleted must be === false
      a.isTerminated must be === false
      a ! 42
      f.isCompleted must be === true
      Await.result(f, timeout.duration) must be === 42
      // clean-up is run as onComplete callback, i.e. dispatched on another thread
      awaitCond(Await.result(c2 ? LookupPath(a.path), timeout.duration).asInstanceOf[ActorRef].isTerminated, 1 second)
    }

  }

  "An ActorSelection" must {

    "send messages directly" in {
      ActorSelection(c1, "") ! GetSender(testActor)
      expectMsg(system.deadLetters)
      lastSender must be === c1
    }

    "send messages with correct sender" in {
      implicit val sender = c1
      ActorSelection(c21, "../../*") ! GetSender(testActor)
      val actors = Set() ++ receiveWhile(messages = 2) {
        case `c1` ⇒ lastSender
      }
      actors must be === Set(c1, c2)
      expectNoMsg(1 second)
    }

    "drop messages which cannot be delivered" in {
      implicit val sender = c2
      ActorSelection(c21, "../../*/c21") ! GetSender(testActor)
      val actors = receiveWhile(messages = 2) {
        case `c2` ⇒ lastSender
      }
      actors must be === Seq(c21)
      expectNoMsg(1 second)
    }

  }

  "An ActorPath" must {

    "support parsing its String rep" in {
      val path = system.actorFor("user").path
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
    }

  }

}