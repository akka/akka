/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor

import scala.concurrent.Await
import scala.concurrent.duration._
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations._
import java.util.concurrent.TimeUnit

object StashCreationBenchmark {
  class StashingActor extends Actor with Stash {
    def receive = {
      case msg ⇒ sender() ! msg
    }
  }

  val props = Props[StashingActor]
}

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.SampleTime))
@Fork(3)
@Warmup(iterations = 5)
@Measurement(iterations = 10)
class StashCreationBenchmark {
  val conf = ConfigFactory.parseString("""
    my-dispatcher = {
      stash-capacity = 1000
    }
    """)
  implicit val system: ActorSystem = ActorSystem("StashCreationBenchmark", conf)
  val probe = TestProbe()

  @TearDown(Level.Trial)
  def shutdown(): Unit = {
    system.terminate()
    Await.ready(system.whenTerminated, 15.seconds)
  }

  @Benchmark
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  def testDefault: Boolean = {
    val stash = system.actorOf(StashCreationBenchmark.props)
    stash.tell("hello", probe.ref)
    probe.expectMsg("hello")
    true
  }

  @Benchmark
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  def testCustom: Boolean = {
    val stash = system.actorOf(StashCreationBenchmark.props.withDispatcher("my-dispatcher"))
    stash.tell("hello", probe.ref)
    probe.expectMsg("hello")
    true
  }
}

