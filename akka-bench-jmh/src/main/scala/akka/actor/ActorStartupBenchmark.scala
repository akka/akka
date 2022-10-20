/*
 * Copyright (C) 2014-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor

import akka.Done
import org.openjdk.jmh.annotations._

import java.util.concurrent.CountDownLatch
import scala.concurrent.duration._
import scala.concurrent.{ Await, Promise }

/*

 */

object ActorStartupBenchmark {
  final val StartupsPerInvocation = 100000

}

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
class ActorStartupBenchmark {
  implicit val system: ActorSystem = ActorSystem()
  import ActorStartupBenchmark._

  var startupLatch: CountDownLatch = _
  var props: Props = _
  var parentProps: Props = _
  var parent: ActorRef = _

  var i = 1
  def name = {
    i += 1
    s"some-rather-long-actor-name-actor-$i-"
  }

  @Setup(Level.Invocation)
  def prepareInvocation(): Unit = {
    startupLatch = new CountDownLatch(StartupsPerInvocation)
    props = Props(new LifecycleLatchingActor(startupLatch))
    parentProps = Props(new StartingParentActor(props))
  }

  @TearDown(Level.Invocation)
  def afterInvocation(): Unit = {
    if (parent ne null) {
      val promise = Promise[Done]()
      parent ! StartingParentActor.Stopit(promise)
      Await.ready(promise.future, 3.seconds)
    }
  }

  @TearDown(Level.Trial)
  def shutdown(): Unit = {
    system.terminate()
    Await.ready(system.whenTerminated, 15.seconds)
  }

  /*
  @Benchmark
  @OperationsPerInvocation(StartupsPerInvocation)
  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  def startupTopLevel() = {
    for (n <- 0 to StartupsPerInvocation) {
      system.actorOf(props, name + n)
    }
    startupLatch.await()
  }
   */

  @Benchmark
  @OperationsPerInvocation(StartupsPerInvocation)
  def startupChildren() = {
    system.actorOf(parentProps, name)
    startupLatch.await()
  }
}

class LifecycleLatchingActor(startupLatch: CountDownLatch) extends Actor {

  override def preStart(): Unit = {
    startupLatch.countDown()
  }

  override def receive: Receive = {
    case _ =>
  }
}

object StartingParentActor {
  case class Stopit(done: Promise[Done])
}

class StartingParentActor(childProps: Props) extends Actor {
  import StartingParentActor._
  var done: Promise[Done] = _
  override def preStart(): Unit = {
    for (_ <- 0 to ActorStartupBenchmark.StartupsPerInvocation) {
      context.actorOf(childProps)
    }
  }

  override def receive: Receive = {
    case Stopit(promise) =>
      done = promise
      context.stop(self)
    case _ =>
  }

  override def postStop(): Unit = {
    done.success(Done)
  }
}
