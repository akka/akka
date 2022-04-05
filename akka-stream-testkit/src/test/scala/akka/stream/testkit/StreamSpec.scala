/*
 * Copyright (C) 2015-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.testkit

import scala.concurrent.Future
import scala.concurrent.duration._

import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.Failed

import akka.actor.{ ActorRef, ActorSystem }
import akka.stream.impl.StreamSupervisor
import akka.stream.snapshot.{ MaterializerState, StreamSnapshotImpl }
import akka.testkit.{ AkkaSpec, TestProbe }
import akka.testkit.TestKitUtils
import akka.stream.impl.PhasedFusingActorMaterializer
import akka.stream.testkit.scaladsl.StreamTestKit.{ assertNoChildren, stopAllChildren }
import akka.stream.Materializer

abstract class StreamSpec(_system: ActorSystem) extends AkkaSpec(_system) {
  def this(config: Config) =
    this(
      ActorSystem(
        TestKitUtils.testNameFromCallStack(classOf[StreamSpec], "".r),
        ConfigFactory.load(config.withFallback(AkkaSpec.testConf))))

  def this(s: String) = this(ConfigFactory.parseString(s))

  def this(configMap: Map[String, _]) = this(AkkaSpec.mapToConfig(configMap))

  def this() = this(ActorSystem(TestKitUtils.testNameFromCallStack(classOf[StreamSpec], "".r), AkkaSpec.testConf))

  override def withFixture(test: NoArgTest) = {
    super.withFixture(test) match {
      case failed: Failed =>
        implicit val ec = system.dispatcher
        val probe = TestProbe()(system)
        // FIXME I don't think it always runs under /user anymore (typed)
        // FIXME correction - I'm not sure this works at _all_ - supposed to dump stream state if test fails
        val streamSupervisors = system.actorSelection("/user/" + StreamSupervisor.baseName + "*")
        streamSupervisors.tell(StreamSupervisor.GetChildren, probe.ref)
        val children: Seq[ActorRef] = probe
          .receiveWhile(2.seconds) {
            case StreamSupervisor.Children(children) => children
          }
          .flatten
        println("--- Stream actors debug dump ---")
        if (children.isEmpty) println("Stream is completed. No debug information is available")
        else {
          println("Stream actors alive: " + children)
          Future
            .sequence(children.map(MaterializerState.requestFromChild))
            .foreach(snapshots =>
              snapshots.foreach(s =>
                akka.stream.testkit.scaladsl.StreamTestKit.snapshotString(s.asInstanceOf[StreamSnapshotImpl])))
        }
        failed
      case other =>
        Materializer(_system) match {
          case impl: PhasedFusingActorMaterializer =>
            stopAllChildren(impl.system, impl.supervisor)
            val result = test.apply()
            assertNoChildren(impl.system, impl.supervisor)
            result
          case _ => other
        }
    }
  }
}
