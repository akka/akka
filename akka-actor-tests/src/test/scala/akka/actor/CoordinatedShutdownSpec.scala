/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor

import scala.concurrent.duration._
import scala.concurrent.Await
import scala.concurrent.Future

import akka.Done
import akka.testkit.AkkaSpec
import com.typesafe.config.ConfigFactory
import akka.actor.CoordinatedShutdown.Phase
import scala.concurrent.Promise
import java.util.concurrent.TimeoutException

class CoordinatedShutdownSpec extends AkkaSpec {

  def extSys = system.asInstanceOf[ExtendedActorSystem]

  // some convenience to make the test readable
  def phase(dependsOn: String*): Phase = Phase(dependsOn.toSet, timeout = 10.seconds, recover = true)
  val emptyPhase: Phase = Phase(Set.empty, timeout = 10.seconds, recover = true)

  private def checkTopologicalSort(phases: Map[String, Phase]): List[String] = {
    val result = CoordinatedShutdown.topologicalSort(phases)
    result.zipWithIndex.foreach {
      case (phase, i) ⇒
        phases.get(phase) match {
          case Some(Phase(dependsOn, _, _)) ⇒
            dependsOn.foreach { depPhase ⇒
              withClue(s"phase [$phase] depends on [$depPhase] but was ordered before it in topological sort result $result") {
                i should be > result.indexOf(depPhase)
              }
            }
          case None ⇒ // ok
        }
    }
    result
  }

  "CoordinatedShutdown" must {

    "sort phases in topolgical order" in {
      checkTopologicalSort(Map.empty) should ===(Nil)

      checkTopologicalSort(Map(
        "a" → emptyPhase)) should ===(List("a"))

      checkTopologicalSort(Map(
        "b" → phase("a"))) should ===(List("a", "b"))

      val result1 = checkTopologicalSort(Map(
        "c" → phase("a"), "b" → phase("a")))
      result1.head should ===("a")
      // b, c can be in any order
      result1.toSet should ===(Set("a", "b", "c"))

      checkTopologicalSort(Map(
        "b" → phase("a"), "c" → phase("b"))) should ===(List("a", "b", "c"))

      checkTopologicalSort(Map(
        "b" → phase("a"), "c" → phase("a", "b"))) should ===(List("a", "b", "c"))

      val result2 = checkTopologicalSort(Map(
        "c" → phase("a", "b")))
      result2.last should ===("c")
      // a, b can be in any order
      result2.toSet should ===(Set("a", "b", "c"))

      checkTopologicalSort(Map(
        "b" → phase("a"), "c" → phase("b"), "d" → phase("b", "c"),
        "e" → phase("d"))) should ===(
        List("a", "b", "c", "d", "e"))

      val result3 = checkTopologicalSort(Map(
        "a2" → phase("a1"), "a3" → phase("a2"),
        "b2" → phase("b1"), "b3" → phase("b2")))
      val (a, b) = result3.partition(_.charAt(0) == 'a')
      a should ===(List("a1", "a2", "a3"))
      b should ===(List("b1", "b2", "b3"))
    }

    "detect cycles in phases (non-DAG)" in {
      intercept[IllegalArgumentException] {
        CoordinatedShutdown.topologicalSort(Map(
          "a" → phase("a")))
      }

      intercept[IllegalArgumentException] {
        CoordinatedShutdown.topologicalSort(Map(
          "b" → phase("a"), "a" → phase("b")))
      }

      intercept[IllegalArgumentException] {
        CoordinatedShutdown.topologicalSort(Map(
          "c" → phase("a"), "c" → phase("b"), "b" → phase("c")))
      }

      intercept[IllegalArgumentException] {
        CoordinatedShutdown.topologicalSort(Map(
          "d" → phase("a"), "d" → phase("c"), "c" → phase("b"), "b" → phase("d")))
      }

    }

    "have pre-defined phases from config" in {
      import CoordinatedShutdown._
      CoordinatedShutdown(system).orderedPhases should ===(List(
        PhaseBeforeServiceUnbind,
        PhaseServiceUnbind,
        PhaseServiceRequestsDone,
        PhaseServiceStop,
        PhaseBeforeClusterShutdown,
        PhaseClusterShardingShutdownRegion,
        PhaseClusterLeave,
        PhaseClusterExiting,
        PhaseClusterExitingDone,
        PhaseClusterShutdown,
        PhaseBeforeActorSystemTerminate,
        PhaseActorSystemTerminate))
    }

    "run ordered phases" in {
      import system.dispatcher
      val phases = Map(
        "a" → emptyPhase,
        "b" → phase("a"),
        "c" → phase("b", "a"))
      val co = new CoordinatedShutdown(extSys, phases)
      co.addTask("a", "a1") { () ⇒
        testActor ! "A"
        Future.successful(Done)
      }
      co.addTask("b", "b1") { () ⇒
        testActor ! "B"
        Future.successful(Done)
      }
      co.addTask("b", "b2") { () ⇒
        Future {
          // to verify that c is not performed before b
          Thread.sleep(100)
          testActor ! "B"
          Done
        }
      }
      co.addTask("c", "c1") { () ⇒
        testActor ! "C"
        Future.successful(Done)
      }
      Await.result(co.run(), remainingOrDefault)
      receiveN(4) should ===(List("A", "B", "B", "C"))
    }

    "run from a given phase" in {
      import system.dispatcher
      val phases = Map(
        "a" → emptyPhase,
        "b" → phase("a"),
        "c" → phase("b", "a"))
      val co = new CoordinatedShutdown(extSys, phases)
      co.addTask("a", "a1") { () ⇒
        testActor ! "A"
        Future.successful(Done)
      }
      co.addTask("b", "b1") { () ⇒
        testActor ! "B"
        Future.successful(Done)
      }
      co.addTask("c", "c1") { () ⇒
        testActor ! "C"
        Future.successful(Done)
      }
      Await.result(co.run(Some("b")), remainingOrDefault)
      receiveN(2) should ===(List("B", "C"))
    }

    "only run once" in {
      import system.dispatcher
      val phases = Map("a" → emptyPhase)
      val co = new CoordinatedShutdown(extSys, phases)
      co.addTask("a", "a1") { () ⇒
        testActor ! "A"
        Future.successful(Done)
      }
      Await.result(co.run(), remainingOrDefault)
      expectMsg("A")
      Await.result(co.run(), remainingOrDefault)
      testActor ! "done"
      expectMsg("done") // no additional A
    }

    "continue after timeout or failure" in {
      import system.dispatcher
      val phases = Map(
        "a" → emptyPhase,
        "b" → Phase(dependsOn = Set("a"), timeout = 100.millis, recover = true),
        "c" → phase("b", "a"))
      val co = new CoordinatedShutdown(extSys, phases)
      co.addTask("a", "a1") { () ⇒
        testActor ! "A"
        Future.failed(new RuntimeException("boom"))
      }
      co.addTask("a", "a2") { () ⇒
        Future {
          // to verify that b is not performed before a also in case of failure
          Thread.sleep(100)
          testActor ! "A"
          Done
        }
      }
      co.addTask("b", "b1") { () ⇒
        testActor ! "B"
        Promise[Done]().future // never completed
      }
      co.addTask("c", "c1") { () ⇒
        testActor ! "C"
        Future.successful(Done)
      }
      Await.result(co.run(), remainingOrDefault)
      expectMsg("A")
      expectMsg("A")
      expectMsg("B")
      expectMsg("C")
    }

    "abort if recover=off" in {
      import system.dispatcher
      val phases = Map(
        "b" → Phase(dependsOn = Set("a"), timeout = 100.millis, recover = false),
        "c" → phase("b", "a"))
      val co = new CoordinatedShutdown(extSys, phases)
      co.addTask("b", "b1") { () ⇒
        testActor ! "B"
        Promise[Done]().future // never completed
      }
      co.addTask("c", "c1") { () ⇒
        testActor ! "C"
        Future.successful(Done)
      }
      val result = co.run()
      expectMsg("B")
      intercept[TimeoutException] {
        Await.result(result, remainingOrDefault)
      }
      expectNoMsg(200.millis) // C not run
    }

    "be possible to add tasks in later phase from task in earlier phase" in {
      import system.dispatcher
      val phases = Map(
        "a" → emptyPhase,
        "b" → phase("a"))
      val co = new CoordinatedShutdown(extSys, phases)
      co.addTask("a", "a1") { () ⇒
        testActor ! "A"
        co.addTask("b", "b1") { () ⇒
          testActor ! "B"
          Future.successful(Done)
        }
        Future.successful(Done)
      }
      Await.result(co.run(), remainingOrDefault)
      expectMsg("A")
      expectMsg("B")
    }

    "parse phases from config" in {
      CoordinatedShutdown.phasesFromConfig(ConfigFactory.parseString("""
        default-phase-timeout = 10s
        phases {
          a = {}
          b {
            depends-on = [a]
            timeout = 15s
          }
          c {
            depends-on = [a, b]
            recover = off
          }
        }
        """)) should ===(Map(
        "a" → Phase(dependsOn = Set.empty, timeout = 10.seconds, recover = true),
        "b" → Phase(dependsOn = Set("a"), timeout = 15.seconds, recover = true),
        "c" → Phase(dependsOn = Set("a", "b"), timeout = 10.seconds, recover = false)))
    }

    // this must be the last test, since it terminates the ActorSystem
    "terminate ActorSystem" in {
      Await.result(CoordinatedShutdown(system).run(), 10.seconds) should ===(Done)
      system.whenTerminated.isCompleted should ===(true)
    }

  }

}
