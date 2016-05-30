package docs.testkit

import org.scalatest.WordSpec
import org.scalatest.Matchers
import akka.testkit.TestKitBase
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.Actor
import akka.actor.ActorRef
import akka.testkit.ImplicitSender
import akka.testkit.TestProbe
import akka.testkit.TestActorRef
import akka.actor.ActorRefFactory
import akka.testkit.TestKit
import org.scalatest.BeforeAndAfterAll

/**
 * Parent-Child examples
 */

//#test-example
class Parent extends Actor {
  val child = context.actorOf(Props[Child], "child")
  var ponged = false

  def receive = {
    case "pingit" => child ! "ping"
    case "pong"   => ponged = true
  }
}

class Child extends Actor {
  def receive = {
    case "ping" => context.parent ! "pong"
  }
}
//#test-example

//#test-dependentchild
class DependentChild(parent: ActorRef) extends Actor {
  def receive = {
    case "ping" => parent ! "pong"
  }
}
//#test-dependentchild

//#test-dependentparent
class DependentParent(childProps: Props) extends Actor {
  val child = context.actorOf(childProps, "child")
  var ponged = false

  def receive = {
    case "pingit" => child ! "ping"
    case "pong"   => ponged = true
  }
}

class GenericDependentParent(childMaker: ActorRefFactory => ActorRef) extends Actor {
  val child = childMaker(context)
  var ponged = false

  def receive = {
    case "pingit" => child ! "ping"
    case "pong"   => ponged = true
  }
}
//#test-dependentparent

/**
 * Test specification
 */

class MockedChild extends Actor {
  def receive = {
    case "ping" => sender ! "pong"
  }
}

class ParentChildSpec extends WordSpec with Matchers with TestKitBase with BeforeAndAfterAll {
  implicit lazy val system = ActorSystem("ParentChildSpec")

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  "A DependentChild" should {
    "be tested without its parent" in {
      val probe = TestProbe()
      val child = system.actorOf(Props(classOf[DependentChild], probe.ref))
      probe.send(child, "ping")
      probe.expectMsg("pong")
    }
  }

  "A DependentParent" should {
    "be tested with custom props" in {
      val probe = TestProbe()
      val parent = TestActorRef(new DependentParent(Props[MockedChild]))
      probe.send(parent, "pingit")
      // test some parent state change
      parent.underlyingActor.ponged should (be(true) or be(false))
    }
  }

  "A GenericDependentParent" should {
    "be tested with a child probe" in {
      val probe = TestProbe()
      //#child-maker-test
      val maker = (_: ActorRefFactory) => probe.ref
      val parent = system.actorOf(Props(classOf[GenericDependentParent], maker))
      //#child-maker-test
      probe.send(parent, "pingit")
      probe.expectMsg("ping")
    }

    "demonstrate production version of child creator" in {
      //#child-maker-prod
      val maker = (f: ActorRefFactory) => f.actorOf(Props[Child])
      val parent = system.actorOf(Props(classOf[GenericDependentParent], maker))
      //#child-maker-prod
    }
  }

  //#test-fabricated-parent
  "A fabricated parent" should {
    "test its child responses" in {
      val proxy = TestProbe()
      val parent = system.actorOf(Props(new Actor {
        val child = context.actorOf(Props[Child], "child")
        def receive = {
          case x if sender == child => proxy.ref forward x
          case x                    => child forward x
        }
      }))

      proxy.send(parent, "ping")
      proxy.expectMsg("pong")
    }
  }
  //#test-fabricated-parent
}
