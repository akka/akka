/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.testkit

import akka.actor.{ ActorSystem, ActorRef }
import akka.stream.impl.StreamSupervisor
import akka.testkit.{ AkkaSpec, TestProbe }
import com.typesafe.config.{ ConfigFactory, Config }
import org.scalatest.Failed
import scala.concurrent.duration._

class StreamSpec(_system: ActorSystem) extends AkkaSpec(_system) {
  def this(config: Config) =
    this(ActorSystem(
      AkkaSpec.getCallerName(getClass),
      ConfigFactory.load(config.withFallback(AkkaSpec.testConf))))

  def this(s: String) = this(ConfigFactory.parseString(s))

  def this(configMap: Map[String, _]) = this(AkkaSpec.mapToConfig(configMap))

  def this() = this(ActorSystem(AkkaSpec.getCallerName(getClass), AkkaSpec.testConf))

  override def withFixture(test: NoArgTest) = {
    super.withFixture(test) match {
      case failed: Failed ⇒
        val probe = TestProbe()(system)
        system.actorSelection("/user/" + StreamSupervisor.baseName + "*").tell(StreamSupervisor.GetChildren, probe.ref)
        val children: Seq[ActorRef] = probe.receiveWhile(2.seconds) {
          case StreamSupervisor.Children(children) ⇒ children
        }.flatten
        println("--- Stream actors debug dump ---")
        if (children.isEmpty) println("Stream is completed. No debug information is available")
        else {
          println("Stream actors alive: " + children)
          children.foreach(_ ! StreamSupervisor.PrintDebugDump)
        }
        failed
      case other ⇒ other
    }
  }
}
