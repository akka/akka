/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import akka.testkit._
import akka.util.duration._

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
  val c21 = (c2 ? Create("c21")).as[ActorRef].get

  val user = system.asInstanceOf[ActorSystemImpl].guardian
  val syst = system.asInstanceOf[ActorSystemImpl].systemGuardian
  val root = system.asInstanceOf[ActorSystemImpl].lookupRoot

  "An ActorSystem" must {

    "find actors by looking up their path" in {
      system.actorFor(c1.path) must be === c1
      system.actorFor(c2.path) must be === c2
      system.actorFor(c21.path) must be === c21
      system.actorFor(system / "c1") must be === c1
      system.actorFor(system / "c2") must be === c2
      system.actorFor(system / "c2" / "c21") must be === c21
      system.actorFor(system / Seq("c2", "c21")) must be === c21
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
      system.actorFor(c21.path.elements) must be === c21
    }

    "find system-generated actors" in {
      system.actorFor("/user") must be === user
      system.actorFor("/null") must be === system.deadLetters
      system.actorFor("/system") must be === syst
      system.actorFor(syst.path) must be === syst
      system.actorFor(syst.path.toString) must be === syst
      system.actorFor("/") must be === root
      system.actorFor("..") must be === root
      system.actorFor(root.path) must be === root
      system.actorFor(root.path.toString) must be === root
      system.actorFor("user") must be === user
      system.actorFor("null") must be === system.deadLetters
      system.actorFor("system") must be === syst
      system.actorFor("user/") must be === user
      system.actorFor("null/") must be === system.deadLetters
      system.actorFor("system/") must be === syst
    }

    "return deadLetters for non-existing paths" in {
      system.actorFor("a/b/c") must be === system.deadLetters
      system.actorFor("") must be === system.deadLetters
      system.actorFor("akka://all-systems/Nobody") must be === system.deadLetters
      system.actorFor("akka://all-systems/user") must be === system.deadLetters
      system.actorFor(system / "hallo") must be === system.deadLetters
      system.actorFor(Seq()) must be === system.deadLetters
      system.actorFor(Seq("a")) must be === system.deadLetters
    }

    "find temporary actors" in {
      val f = c1 ? GetSender(testActor)
      val a = expectMsgType[ActorRef]
      a.path.elements.head must be === "temp"
      system.actorFor(a.path) must be === a
      system.actorFor(a.path.toString) must be === a
      system.actorFor(a.path.elements) must be === a
      system.actorFor(a.path.toString + "/") must be === a
      system.actorFor(a.path.toString + "/hallo") must be === system.deadLetters
      f.isCompleted must be === false
      a ! 42
      f.isCompleted must be === true
      f.get must be === 42
      system.actorFor(a.path) must be === system.deadLetters
    }

  }

  "An ActorContext" must {

    val all = Seq(c1, c2, c21)

    "find actors by looking up their path" in {
      def check(looker: ActorRef, pathOf: ActorRef, result: ActorRef) {
        (looker ? LookupPath(pathOf.path)).get must be === result
      }
      for {
        looker ← all
        target ← all
      } check(looker, target, target)
    }

    "find actors by looking up their string representation" in {
      def check(looker: ActorRef, pathOf: ActorRef, result: ActorRef) {
        (looker ? LookupString(pathOf.path.toString)).get must be === result
        (looker ? LookupString(pathOf.path.toString + "/")).get must be === result
      }
      for {
        looker ← all
        target ← all
      } check(looker, target, target)
    }

    "find actors by looking up their root-anchored relative path" in {
      def check(looker: ActorRef, pathOf: ActorRef, result: ActorRef) {
        (looker ? LookupString(pathOf.path.elements.mkString("/", "/", ""))).get must be === result
        (looker ? LookupString(pathOf.path.elements.mkString("/", "/", "/"))).get must be === result
      }
      for {
        looker ← all
        target ← all
      } check(looker, target, target)
    }

    "find actors by looking up their relative path" in {
      def check(looker: ActorRef, result: ActorRef, elems: String*) {
        (looker ? LookupElems(elems)).get must be === result
        (looker ? LookupString(elems mkString "/")).get must be === result
        (looker ? LookupString(elems mkString ("", "/", "/"))).get must be === result
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
          (looker ? LookupPath(target.path)).get must be === target
          (looker ? LookupString(target.path.toString)).get must be === target
          (looker ? LookupString(target.path.toString + "/")).get must be === target
          (looker ? LookupString(target.path.elements.mkString("/", "/", ""))).get must be === target
          if (target != root) (looker ? LookupString(target.path.elements.mkString("/", "/", "/"))).get must be === target
        }
      }
      for (target ← Seq(root, syst, user, system.deadLetters)) check(target)
    }

    "return deadLetters for non-existing paths" in {
      def checkOne(looker: ActorRef, query: Query) {
        (looker ? query).get must be === system.deadLetters
      }
      def check(looker: ActorRef) {
        Seq(LookupString("a/b/c"),
          LookupString(""),
          LookupString("akka://all-systems/Nobody"),
          LookupPath(system / "hallo"),
          LookupElems(Seq()),
          LookupElems(Seq("a"))) foreach (checkOne(looker, _))
      }
      for (looker ← all) check(looker)
    }

    "find temporary actors" in {
      val f = c1 ? GetSender(testActor)
      val a = expectMsgType[ActorRef]
      a.path.elements.head must be === "temp"
      (c2 ? LookupPath(a.path)).get must be === a
      (c2 ? LookupString(a.path.toString)).get must be === a
      (c2 ? LookupString(a.path.elements.mkString("/", "/", ""))).get must be === a
      (c2 ? LookupString("../../" + a.path.elements.mkString("/"))).get must be === a
      (c2 ? LookupString(a.path.toString + "/")).get must be === a
      (c2 ? LookupString(a.path.elements.mkString("/", "/", "") + "/")).get must be === a
      (c2 ? LookupString("../../" + a.path.elements.mkString("/") + "/")).get must be === a
      (c2 ? LookupElems(Seq("..", "..") ++ a.path.elements)).get must be === a
      (c2 ? LookupElems(Seq("..", "..") ++ a.path.elements :+ "")).get must be === a
      f.isCompleted must be === false
      a ! 42
      f.isCompleted must be === true
      f.get must be === 42
      (c2 ? LookupPath(a.path)).get must be === system.deadLetters
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
      val actors = receiveWhile(messages = 2) {
        case `c1` ⇒ lastSender
      }
      actors must be === Seq(c1, c2)
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

}