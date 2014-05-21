/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.event

import org.openjdk.jmh.annotations._
import com.typesafe.config.ConfigFactory
import akka.testkit.TestProbe
import akka.actor.{ActorRef, ActorSystem}

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
class EventStreamSubscriptionsBenchmark {

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

  var system: ActorSystem = _
  var eventStream: EventStream = _

  var subsProbe: TestProbe = _
  var subs: ActorRef = _

  trait A
  trait B
  trait AB extends A with B

  final val channelA = classOf[A]
  final val channelB = classOf[B]
  final val channelAB = classOf[AB]

  @Setup(Level.Iteration)
  def setup() {
    system = ActorSystem("test", config)

    eventStream = system.eventStream
    subsProbe = TestProbe()(system)
    subs = subsProbe.ref
  }

  @TearDown(Level.Iteration)
  def shutdown() {
    system.shutdown()
    system.awaitTermination()
  }

  @Threads(1)
  @GenerateMicroBenchmark
  def subscribe_unsubscribe_same_actor_1_thread() {
    eventStream.subscribe(subs, channelA)
    eventStream.unsubscribe(subs)
  }

  @Threads(8)
  @GenerateMicroBenchmark
  def subscribe_unsubscribe_same_actor_8_threads() {
    eventStream.subscribe(subs, channelA)
    eventStream.unsubscribe(subs)
  }

  @Group("subscribe_vs_unsubscribe")
  @GenerateMicroBenchmark
  def subscribing_in_group_vs_unsubscribing() {
    eventStream.subscribe(subs, channelA)
  }

  @Group("subscribe_vs_unsubscribe")
  @GenerateMicroBenchmark
  def unsubscribing_in_group_vs_subscribing() {
    eventStream.unsubscribe(subs)
  }

}



