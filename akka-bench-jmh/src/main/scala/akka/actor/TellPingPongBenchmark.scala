/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import org.openjdk.jmh.annotations._
import com.typesafe.config.ConfigFactory
import akka.testkit.TestProbe
import scala.concurrent.duration._
import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
class TellPingPongBenchmark {

  val config = ConfigFactory.parseString("""
       akka {
         log-config-on-start = off
         log-dead-letters-during-shutdown = off
         loglevel = "WARNING"

         test {
           timefactor =  1.0
           filter-leeway = 3s
           single-expect-default = 3s
           default-timeout = 5s
           calling-thread-dispatcher {
             type = akka.testkit.CallingThreadDispatcherConfigurator
           }
         }
       }""".stripMargin).withFallback(ConfigFactory.load())

  implicit val system = ActorSystem("test", config)

  val probe = TestProbe()
  implicit val testActor = probe.ref

  var worker: ActorRef = _
  var waiter: ActorRef = _

  @Setup
  def setup() {
    worker = system.actorOf(Props[Worker], "worker")
    waiter = system.actorOf(Props(classOf[Waiter], probe.ref), "waiter")
  }

  @TearDown
  def shutdown() {
    system.shutdown()
    system.awaitTermination()
  }


  @OutputTimeUnit(TimeUnit.MILLISECONDS)
  @BenchmarkMode(Array(Mode.Throughput))
  @OperationsPerInvocation(200000) // twice as much messages are sent, because ping<->pong
  @GenerateMicroBenchmark
  def tell_100000_msgs_a() {
    waiter ! AwaitUntil(100000)
    worker.tell("ping", waiter)

    probe.expectMsg(60.seconds, GotLast)
  }

  @OutputTimeUnit(TimeUnit.MICROSECONDS)
  @BenchmarkMode(Array(Mode.SampleTime))
  @OperationsPerInvocation(200000) // twice as much messages are sent, because ping<->pong
  @GenerateMicroBenchmark
  def tell_100000_msgs_b() {
    waiter ! AwaitUntil(100000)
    worker.tell("ping", waiter)

    probe.expectMsg(60.seconds, GotLast)
  }

}

final case class AwaitUntil(n: Long)
case object GotLast

class Worker extends Actor {
  def receive = {
    case _ => sender() ! "ping"
  }
}

class Waiter(notifyWhenDone: ActorRef) extends Actor {
  var until = 1L

  def receive = {
    case AwaitUntil(n) =>
      until = n
      context.become(waiting(n))
  }

  def waiting(n: Long): Actor.Receive = {
    case _ if until <= 0 =>
      notifyWhenDone ! GotLast

    case _ =>
      until -= 1
      sender() ! "pong"
  }
}


