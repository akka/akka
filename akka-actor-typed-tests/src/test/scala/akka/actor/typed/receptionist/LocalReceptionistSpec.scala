/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor.typed.receptionist

import akka.actor.typed._
import akka.actor.typed.receptionist.Receptionist._
import akka.actor.typed.scaladsl.Actor
import akka.actor.typed.scaladsl.AskPattern._
import akka.testkit.typed.{ BehaviorTestkit, TestInbox, TestKit, TestKitSettings }
import akka.testkit.typed.scaladsl.TestProbe
import org.scalatest.concurrent.Eventually

import scala.concurrent.Future

class LocalReceptionistSpec extends TestKit with TypedAkkaSpecWithShutdown with Eventually {

  trait ServiceA
  val ServiceKeyA = Receptionist.ServiceKey[ServiceA]("service-a")
  val behaviorA = Actor.empty[ServiceA]

  trait ServiceB
  val ServiceKeyB = Receptionist.ServiceKey[ServiceB]("service-b")
  val behaviorB = Actor.empty[ServiceB]

  case object Stop extends ServiceA with ServiceB
  val stoppableBehavior = Actor.immutable[Any] { (_, msg) ⇒
    msg match {
      case Stop ⇒ Behavior.stopped
      case _    ⇒ Behavior.same
    }
  }

  import akka.actor.typed.internal.receptionist.ReceptionistImpl.{ localOnlyBehavior ⇒ receptionistBehavior }

  implicit val testSettings = TestKitSettings(system)

  abstract class TestSetup {
    val receptionist = spawn(receptionistBehavior)
  }

  "A local receptionist" must {

    "register a service" in {
      val ctx = new BehaviorTestkit("register", receptionistBehavior)
      val a = TestInbox[ServiceA]("a")
      val r = TestInbox[Registered[_]]("r")
      ctx.run(Register(ServiceKeyA, a.ref)(r.ref))
      ctx.retrieveEffect() // watching however that is implemented
      r.receiveMsg() should be(Registered(ServiceKeyA, a.ref))
      val q = TestInbox[Listing[ServiceA]]("q")
      ctx.run(Find(ServiceKeyA)(q.ref))
      ctx.retrieveAllEffects() should be(Nil)
      q.receiveMsg() should be(Listing(ServiceKeyA, Set(a.ref)))
      assertEmpty(a, r, q)
    }

    "register two services" in {
      val ctx = new BehaviorTestkit("registertwo", receptionistBehavior)
      val a = TestInbox[ServiceA]("a")
      val r = TestInbox[Registered[_]]("r")
      ctx.run(Register(ServiceKeyA, a.ref)(r.ref))
      r.receiveMsg() should be(Registered(ServiceKeyA, a.ref))
      val b = TestInbox[ServiceB]("b")
      ctx.run(Register(ServiceKeyB, b.ref)(r.ref))
      r.receiveMsg() should be(Registered(ServiceKeyB, b.ref))
      val q = TestInbox[Listing[_]]("q")
      ctx.run(Find(ServiceKeyA)(q.ref))
      q.receiveMsg() should be(Listing(ServiceKeyA, Set(a.ref)))
      ctx.run(Find(ServiceKeyB)(q.ref))
      q.receiveMsg() should be(Listing(ServiceKeyB, Set(b.ref)))
      assertEmpty(a, b, r, q)
    }

    "register two services with the same key" in {
      val ctx = new BehaviorTestkit("registertwosame", receptionistBehavior)
      val a1 = TestInbox[ServiceA]("a1")
      val r = TestInbox[Registered[_]]("r")
      ctx.run(Register(ServiceKeyA, a1.ref)(r.ref))
      r.receiveMsg() should be(Registered(ServiceKeyA, a1.ref))
      val a2 = TestInbox[ServiceA]("a2")
      ctx.run(Register(ServiceKeyA, a2.ref)(r.ref))
      r.receiveMsg() should be(Registered(ServiceKeyA, a2.ref))
      val q = TestInbox[Listing[_]]("q")
      ctx.run(Find(ServiceKeyA)(q.ref))
      q.receiveMsg() should be(Listing(ServiceKeyA, Set(a1.ref, a2.ref)))
      ctx.run(Find(ServiceKeyB)(q.ref))
      q.receiveMsg() should be(Listing(ServiceKeyB, Set.empty[ActorRef[ServiceB]]))
      assertEmpty(a1, a2, r, q)
    }

    "unregister services when they terminate" in {
      new TestSetup {
        val regProbe = TestProbe[Any]("regProbe")

        val serviceA = spawn(stoppableBehavior.narrow[ServiceA])
        receptionist ! Register(ServiceKeyA, serviceA, regProbe.ref)
        regProbe.expectMsg(Registered(ServiceKeyA, serviceA))

        val serviceB = spawn(stoppableBehavior.narrow[ServiceB])
        receptionist ! Register(ServiceKeyB, serviceB, regProbe.ref)
        regProbe.expectMsg(Registered(ServiceKeyB, serviceB))

        val serviceC = spawn(stoppableBehavior)
        receptionist ! Register(ServiceKeyA, serviceC, regProbe.ref)
        receptionist ! Register(ServiceKeyB, serviceC, regProbe.ref)
        regProbe.expectMsg(Registered(ServiceKeyA, serviceC))
        regProbe.expectMsg(Registered(ServiceKeyB, serviceC))

        receptionist ! Find(ServiceKeyA, regProbe.ref)
        regProbe.expectMsg(Listing(ServiceKeyA, Set(serviceA, serviceC)))
        receptionist ! Find(ServiceKeyB, regProbe.ref)
        regProbe.expectMsg(Listing(ServiceKeyB, Set(serviceB, serviceC)))

        serviceC ! Stop

        eventually {
          receptionist ! Find(ServiceKeyA, regProbe.ref)
          regProbe.expectMsg(Listing(ServiceKeyA, Set(serviceA)))
          receptionist ! Find(ServiceKeyB, regProbe.ref)
          regProbe.expectMsg(Listing(ServiceKeyB, Set(serviceB)))
        }
      }
    }

    "support subscribing to service changes" in {
      new TestSetup {
        val regProbe = TestProbe[Registered[_]]("regProbe")

        val aSubscriber = TestProbe[Listing[ServiceA]]("aUser")
        receptionist ! Subscribe(ServiceKeyA, aSubscriber.ref)

        aSubscriber.expectMsg(Listing(ServiceKeyA, Set.empty[ActorRef[ServiceA]]))

        val serviceA: ActorRef[ServiceA] = spawn(stoppableBehavior)
        receptionist ! Register(ServiceKeyA, serviceA, regProbe.ref)
        regProbe.expectMsg(Registered(ServiceKeyA, serviceA))

        aSubscriber.expectMsg(Listing(ServiceKeyA, Set(serviceA)))

        val serviceA2: ActorRef[ServiceA] = spawn(stoppableBehavior)
        receptionist ! Register(ServiceKeyA, serviceA2, regProbe.ref)
        regProbe.expectMsg(Registered(ServiceKeyA, serviceA2))

        aSubscriber.expectMsg(Listing(ServiceKeyA, Set(serviceA, serviceA2)))

        serviceA ! Stop
        aSubscriber.expectMsg(Listing(ServiceKeyA, Set(serviceA2)))
        serviceA2 ! Stop
        aSubscriber.expectMsg(Listing(ServiceKeyA, Set.empty[ActorRef[ServiceA]]))
      }
    }

    "work with ask" in {
      val receptionist = spawn(receptionistBehavior)
      val serviceA = spawn(behaviorA)
      val f: Future[Registered[ServiceA]] = receptionist ? Register(ServiceKeyA, serviceA)
      f.futureValue should be(Registered(ServiceKeyA, serviceA))
    }

    "be present in the system" in {
      val probe = TestProbe[Receptionist.Listing[_]]()
      system.receptionist ! Find(ServiceKeyA)(probe.ref)
      val listing: Listing[_] = probe.expectMsgType[Listing[_]]
      listing.serviceInstances should be(Set())
    }
  }
}
