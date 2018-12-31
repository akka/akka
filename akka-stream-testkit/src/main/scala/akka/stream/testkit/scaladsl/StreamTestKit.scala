/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.testkit.scaladsl

import akka.actor.{ ActorRef, ActorSystem }
import akka.annotation.InternalApi
import akka.stream.Materializer
import akka.stream.impl.{ PhasedFusingActorMaterializer, StreamSupervisor }
import akka.testkit.TestProbe

import scala.concurrent.duration._

object StreamTestKit {

  /**
   * Asserts that after the given code block is ran, no stages are left over
   * that were created by the given materializer.
   *
   * This assertion is useful to check that all of the stages have
   * terminated successfully.
   */
  def assertAllStagesStopped[T](block: ⇒ T)(implicit materializer: Materializer): T =
    materializer match {
      case impl: PhasedFusingActorMaterializer ⇒
        stopAllChildren(impl.system, impl.supervisor)
        val result = block
        assertNoChildren(impl.system, impl.supervisor)
        result
      case _ ⇒ block
    }

  /** INTERNAL API */
  @InternalApi private[testkit] def stopAllChildren(sys: ActorSystem, supervisor: ActorRef): Unit = {
    val probe = TestProbe()(sys)
    probe.send(supervisor, StreamSupervisor.StopChildren)
    probe.expectMsg(StreamSupervisor.StoppedChildren)
  }

  /** INTERNAL API */
  @InternalApi private[testkit] def assertNoChildren(sys: ActorSystem, supervisor: ActorRef): Unit = {
    val probe = TestProbe()(sys)
    probe.within(5.seconds) {
      var children = Set.empty[ActorRef]
      try probe.awaitAssert {
        supervisor.tell(StreamSupervisor.GetChildren, probe.ref)
        children = probe.expectMsgType[StreamSupervisor.Children].children
        assert(
          children.isEmpty,
          s"expected no StreamSupervisor children, but got [${children.mkString(", ")}]")
      }
      catch {
        case ex: Throwable ⇒
          children.foreach(_ ! StreamSupervisor.PrintDebugDump)
          throw ex
      }
    }
  }

}

