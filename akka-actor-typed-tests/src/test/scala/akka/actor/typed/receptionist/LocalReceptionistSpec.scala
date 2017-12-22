/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor.typed.receptionist

import akka.actor.typed._
import akka.actor.typed.receptionist.Receptionist._
import akka.actor.typed.scaladsl.Actor
import akka.actor.typed.scaladsl.AskPattern._
import akka.testkit.typed.BehaviorTestkit
import akka.testkit.typed.TestInbox
import akka.testkit.typed.TestKitSettings
import akka.testkit.typed.scaladsl.TestProbe
import org.scalatest.concurrent.Eventually

import scala.concurrent.duration._

class LocalReceptionistSpec extends TypedSpec with Eventually with StartSupport {

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

  import akka.actor.typed.internal.receptionist.ReceptionistImpl.{ localOnlyBehavior ⇒ behavior }

  implicit val testSettings = TestKitSettings(system)

  abstract class TestSetup {
    val receptionist = start(behavior)
  }

  "A local receptionist" must {

    "must register a service" in {
      val ctx = new BehaviorTestkit("register", behavior)
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

    "must register two services" in {
      val ctx = new BehaviorTestkit("registertwo", behavior)
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

    "must register two services with the same key" in {
      val ctx = new BehaviorTestkit("registertwosame", behavior)
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

    "must unregister services when they terminate" in {
      new TestSetup {
        val regProbe = TestProbe[Any]("regProbe")

        val serviceA = start(stoppableBehavior.narrow[ServiceA])
        receptionist ! Register(ServiceKeyA, serviceA, regProbe.ref)
        regProbe.expectMsg(Registered(ServiceKeyA, serviceA))

        val serviceB = start(stoppableBehavior.narrow[ServiceB])
        receptionist ! Register(ServiceKeyB, serviceB, regProbe.ref)
        regProbe.expectMsg(Registered(ServiceKeyB, serviceB))

        val serviceC = start(stoppableBehavior)
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

    "must support subscribing to service changes" in {
      new TestSetup {
        val regProbe = TestProbe[Registered[_]]("regProbe")

        val aSubscriber = TestProbe[Listing[ServiceA]]("aUser")
        receptionist ! Subscribe(ServiceKeyA, aSubscriber.ref)

        aSubscriber.expectMsg(Listing(ServiceKeyA, Set.empty[ActorRef[ServiceA]]))

        val serviceA: ActorRef[ServiceA] = start(stoppableBehavior)
        receptionist ! Register(ServiceKeyA, serviceA, regProbe.ref)
        regProbe.expectMsg(Registered(ServiceKeyA, serviceA))

        aSubscriber.expectMsg(Listing(ServiceKeyA, Set(serviceA)))

        val serviceA2: ActorRef[ServiceA] = start(stoppableBehavior)
        receptionist ! Register(ServiceKeyA, serviceA2, regProbe.ref)
        regProbe.expectMsg(Registered(ServiceKeyA, serviceA2))

        aSubscriber.expectMsg(Listing(ServiceKeyA, Set(serviceA, serviceA2)))

        serviceA ! Stop
        aSubscriber.expectMsg(Listing(ServiceKeyA, Set(serviceA2)))
        serviceA2 ! Stop
        aSubscriber.expectMsg(Listing(ServiceKeyA, Set.empty[ActorRef[ServiceA]]))
      }
    }

    "must work with ask" in {
      sync(runTest("Receptionist") {
        StepWise[Registered[ServiceA]] { (ctx, startWith) ⇒
          val self = ctx.self
          startWith.withKeepTraces(true) {
            val r = ctx.spawnAnonymous(behavior)
            val s = ctx.spawnAnonymous(behaviorA)
            val f = r ? Register(ServiceKeyA, s)
            r ! Register(ServiceKeyA, s)(self)
            (f, s)
          }.expectMessage(1.second) {
            case (msg, (f, s)) ⇒
              msg should be(Registered(ServiceKeyA, s))
              f.foreach(self ! _)(system.executionContext)
              s
          }.expectMessage(1.second) {
            case (msg, s) ⇒
              msg should be(Registered(ServiceKeyA, s))
          }
        }
      })
    }

    "must be present in the system" in {
      sync(runTest("systemReceptionist") {
        StepWise[Listing[ServiceA]] { (ctx, startWith) ⇒
          val self = ctx.self
          startWith.withKeepTraces(true) {
            system.receptionist ! Find(ServiceKeyA)(self)
          }.expectMessage(1.second) { (msg, _) ⇒
            msg.serviceInstances should ===(Set())
          }
        }
      })
    }
  }
}
