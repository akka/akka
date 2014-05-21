/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.event

import org.openjdk.jmh.annotations._
import com.typesafe.config.ConfigFactory
import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.TestProbe

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
class EventStreamPublishingBenchmark {

  val config = ConfigFactory.parseString( """
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

  class A
  class B
  class C

  final val channelA = classOf[A]
  final val channelB = classOf[B]
  final val channelC = classOf[C]

  final val a = new A
  final val b = new B
  final val c = new C

  @Param(Array("1", "100", "1000"))
  var subscribersOfA = 0

  @Param(Array("0", "1", "100"))
  var subscribersOfB = 0

  @Setup(Level.Iteration)
  def setup() {
    system = ActorSystem("test", config)
    eventStream = system.eventStream

    1 to subscribersOfA foreach { i => eventStream.subscribe(TestProbe()(system).ref, channelA) }
    1 to subscribersOfB foreach { i => eventStream.subscribe(TestProbe()(system).ref, channelB) }
  }

  @TearDown(Level.Iteration)
  def shutdown() {
    system.shutdown()
    system.awaitTermination()
  }

  @Threads(1)
  @GenerateMicroBenchmark
  def publish_matching_events_to_event_stream_1_thread() {
    eventStream.publish(a)
  }

  @Threads(8)
  @GenerateMicroBenchmark
  def publish_matching_events_to_event_stream_8_threads() {
    eventStream.publish(a)
  }

  @Threads(1)
  @GenerateMicroBenchmark
  def publish_notMatching_events_to_event_stream_1_threads() {
    eventStream.publish(c)
  }

  @Threads(8)
  @GenerateMicroBenchmark
  def publish_notMatching_events_to_event_stream_8_threads() {
    eventStream.publish(c)
  }

}

