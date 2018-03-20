/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations._

import scala.concurrent.Await
import scala.concurrent.duration._

/*
without caching:
[info] ActorCreationWithReflectionCacheBenchmark.synchronousStarting    ss  100  165.216 ± 8.644  us/op

with caching:
[info] ActorCreationWithReflectionCacheBenchmark.synchronousStarting    ss  100  159.387 ± 10.817  us/op

*/
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.SingleShotTime))
@Fork(5)
@Warmup(iterations = 1000)
@Measurement(iterations = 50000)
class ActorCreationWithReflectionCacheBenchmark {
  implicit val system: ActorSystem = ActorSystem()

  var i = 1
  def name = {
    i += 1
    "some-rather-long-actor-name-actor-" + i
  }

  @TearDown(Level.Trial)
  def shutdown(): Unit = {
    system.terminate()
    Await.ready(system.whenTerminated, 15.seconds)
  }

  var current = 0
  @Benchmark
  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  def synchronousStarting =
    system.actorOf(Props(classOf[MyNamedActor], 1, "2"), name)
}

class MyNamedActor(arg1: Int, arg2: String) extends Actor {
  override def receive: Receive = {
    case _ ⇒
  }
}
