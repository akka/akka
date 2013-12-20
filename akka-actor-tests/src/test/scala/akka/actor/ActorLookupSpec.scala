/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import language.postfixOps

import akka.testkit._
import scala.concurrent.duration._
import scala.concurrent.Await
import akka.pattern.ask

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
      system.actorFor(c1.path) should equal(c1)
      system.actorFor(c2.path) should equal(c2)
      system.actorFor(c21.path) should equal(c21)
      system.actorFor(system / "c1") should equal(c1)
      system.actorFor(system / "c2") should equal(c2)
      system.actorFor(system / "c2" / "c21") should equal(c21)
      system.actorFor(system child "c2" child "c21") should equal(c21) // test Java API
      system.actorFor(system / Seq("c2", "c21")) should equal(c21)

      import scala.collection.JavaConverters._
      system.actorFor(system descendant Seq("c2", "c21").asJava) // test Java API
    }

    "find actors by looking up their string representation" in {
      // this is only true for local actor references
      system.actorFor(c1.path.toString) should equal(c1)
      system.actorFor(c2.path.toString) should equal(c2)
      system.actorFor(c21.path.toString) should equal(c21)
    }

    "take actor incarnation into account when comparing actor references" in {
      val name = "abcdefg"
      val a1 = system.actorOf(p, name)
      watch(a1)
      a1 ! PoisonPill
      expectTerminated(a1)

      // let it be completely removed from user guardian
      expectNoMsg(1 second)

      // not equal because it's terminated
      system.actorFor(a1.path.toString) should not be (a1)

      val a2 = system.actorOf(p, name)
      a2.path should be(a1.path)
      a2.path.toString should be(a1.path.toString)
      a2 should not be (a1)
      a2.toString should not be (a1.toString)

      watch(a2)
      a2 ! PoisonPill
      expectTerminated(a2)
    }

    "find actors by looking up their root-anchored relative path" in {
      system.actorFor(c1.path.elements.mkString("/", "/", "")) should equal(c1)
      system.actorFor(c2.path.elements.mkString("/", "/", "")) should equal(c2)
      system.actorFor(c21.path.elements.mkString("/", "/", "")) should equal(c21)
    }

    "find actors by looking up their relative path" in {
      system.actorFor(c1.path.elements.mkString("/")) should equal(c1)
      system.actorFor(c2.path.elements.mkString("/")) should equal(c2)
      system.actorFor(c21.path.elements.mkString("/")) should equal(c21)
    }

    "find actors by looking up their path elements" in {
      system.actorFor(c1.path.elements) should equal(c1)
      system.actorFor(c2.path.elements) should equal(c2)
      system.actorFor(c21.path.getElements) should equal(c21) // test Java API
    }

    "find system-generated actors" in {
      system.actorFor("/user") should equal(user)
      system.actorFor("/deadLetters") should equal(system.deadLetters)
      system.actorFor("/system") should equal(syst)
      system.actorFor(syst.path) should equal(syst)
      system.actorFor(syst.path.toString) should equal(syst)
      system.actorFor("/") should equal(root)
      system.actorFor("..") should equal(root)
      system.actorFor(root.path) should equal(root)
      system.actorFor(root.path.toString) should equal(root)
      system.actorFor("user") should equal(user)
      system.actorFor("deadLetters") should equal(system.deadLetters)
      system.actorFor("system") should equal(syst)
      system.actorFor("user/") should equal(user)
      system.actorFor("deadLetters/") should equal(system.deadLetters)
      system.actorFor("system/") should equal(syst)
    }

    "return deadLetters or EmptyLocalActorRef, respectively, for non-existing paths" in {
      def check(lookup: ActorRef, result: ActorRef) = {
        lookup.getClass should equal(result.getClass)
        lookup should equal(result)
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
      a.path.elements.head should equal("temp")
      system.actorFor(a.path) should equal(a)
      system.actorFor(a.path.toString) should equal(a)
      system.actorFor(a.path.elements) should equal(a)
      system.actorFor(a.path.toString + "/") should equal(a)
      system.actorFor(a.path.toString + "/hallo").isTerminated should equal(true)
      f.isCompleted should equal(false)
      a.isTerminated should equal(false)
      a ! 42
      f.isCompleted should equal(true)
      Await.result(f, timeout.duration) should equal(42)
      // clean-up is run as onComplete callback, i.e. dispatched on another thread
      awaitCond(system.actorFor(a.path).isTerminated, 1 second)
    }

  }

  "An ActorContext" must {

    val all = Seq(c1, c2, c21)

    "find actors by looking up their path" in {
      def check(looker: ActorRef, pathOf: ActorRef, result: ActorRef) {
        Await.result(looker ? LookupPath(pathOf.path), timeout.duration) should equal(result)
      }
      for {
        looker ← all
        target ← all
      } check(looker, target, target)
    }

    "find actors by looking up their string representation" in {
      def check(looker: ActorRef, pathOf: ActorRef, result: ActorRef) {
        Await.result(looker ? LookupString(pathOf.path.toString), timeout.duration) should equal(result)
        // with uid
        Await.result(looker ? LookupString(pathOf.path.toSerializationFormat), timeout.duration) should equal(result)
        // with trailing /
        Await.result(looker ? LookupString(pathOf.path.toString + "/"), timeout.duration) should equal(result)
      }
      for {
        looker ← all
        target ← all
      } check(looker, target, target)
    }

    "find actors by looking up their root-anchored relative path" in {
      def check(looker: ActorRef, pathOf: ActorRef, result: ActorRef) {
        Await.result(looker ? LookupString(pathOf.path.elements.mkString("/", "/", "")), timeout.duration) should equal(result)
        Await.result(looker ? LookupString(pathOf.path.elements.mkString("/", "/", "/")), timeout.duration) should equal(result)
      }
      for {
        looker ← all
        target ← all
      } check(looker, target, target)
    }

    "find actors by looking up their relative path" in {
      def check(looker: ActorRef, result: ActorRef, elems: String*) {
        Await.result(looker ? LookupElems(elems), timeout.duration) should equal(result)
        Await.result(looker ? LookupString(elems mkString "/"), timeout.duration) should equal(result)
        Await.result(looker ? LookupString(elems mkString ("", "/", "/")), timeout.duration) should equal(result)
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
          Await.result(looker ? LookupPath(target.path), timeout.duration) should equal(target)
          Await.result(looker ? LookupString(target.path.toString), timeout.duration) should equal(target)
          Await.result(looker ? LookupString(target.path.toString + "/"), timeout.duration) should equal(target)
          Await.result(looker ? LookupString(target.path.elements.mkString("/", "/", "")), timeout.duration) should equal(target)
          if (target != root) Await.result(looker ? LookupString(target.path.elements.mkString("/", "/", "/")), timeout.duration) should equal(target)
        }
      }
      for (target ← Seq(root, syst, user, system.deadLetters)) check(target)
    }

    "return deadLetters or EmptyLocalActorRef, respectively, for non-existing paths" in {
      import scala.collection.JavaConverters._

      def checkOne(looker: ActorRef, query: Query, result: ActorRef) {
        val lookup = Await.result(looker ? query, timeout.duration)
        lookup.getClass should equal(result.getClass)
        lookup should equal(result)
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
      a.path.elements.head should equal("temp")
      Await.result(c2 ? LookupPath(a.path), timeout.duration) should equal(a)
      Await.result(c2 ? LookupString(a.path.toString), timeout.duration) should equal(a)
      Await.result(c2 ? LookupString(a.path.elements.mkString("/", "/", "")), timeout.duration) should equal(a)
      Await.result(c2 ? LookupString("../../" + a.path.elements.mkString("/")), timeout.duration) should equal(a)
      Await.result(c2 ? LookupString(a.path.toString + "/"), timeout.duration) should equal(a)
      Await.result(c2 ? LookupString(a.path.elements.mkString("/", "/", "") + "/"), timeout.duration) should equal(a)
      Await.result(c2 ? LookupString("../../" + a.path.elements.mkString("/") + "/"), timeout.duration) should equal(a)
      Await.result(c2 ? LookupElems(Seq("..", "..") ++ a.path.elements), timeout.duration) should equal(a)
      Await.result(c2 ? LookupElems(Seq("..", "..") ++ a.path.elements :+ ""), timeout.duration) should equal(a)
      f.isCompleted should equal(false)
      a.isTerminated should equal(false)
      a ! 42
      f.isCompleted should equal(true)
      Await.result(f, timeout.duration) should equal(42)
      // clean-up is run as onComplete callback, i.e. dispatched on another thread
      awaitCond(Await.result(c2 ? LookupPath(a.path), timeout.duration).asInstanceOf[ActorRef].isTerminated, 1 second)
    }

  }

}
