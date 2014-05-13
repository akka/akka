/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import org.openjdk.jmh.annotations._
import com.typesafe.config.ConfigFactory
import akka.testkit.TestProbe
import scala.concurrent.duration._
import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
class ActorCreationBenchmark {

  val config = ConfigFactory.parseString(
    """
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


//  @Param(Array("100000"))
  val numberOfActors = 100000

  @Setup
  def setup() {
  }

  @TearDown
  def shutdown() {
    system.shutdown()
    system.awaitTermination()
  }

  import ActorCreationPerfSpec._

  @GenerateMicroBenchmark
  @OperationsPerInvocation(100000)
  def actor_creation_time_for_actorOf_Props_EmptyActor__each_time_new__100k_actors() {
    val propsCreator = () => Props[EmptyActor]

    test(propsCreator)
  }

  @GenerateMicroBenchmark
  @OperationsPerInvocation(100000)
  def actor_creation_time_for_actorOf_Props_EmptyActor__each_time_same__100k_actors() {
    val p = Props[EmptyActor]
    val propsCreator = () => p

    test(propsCreator)
  }

  @GenerateMicroBenchmark
  @OperationsPerInvocation(100000)
  def actor_creation_time_for_actorOf_Props_new_EmptyActor__each_time_new__100k_actors() {
    val propsCreator = () => { Props(new EmptyActor) }

    test(propsCreator)
  }

  @GenerateMicroBenchmark
  @OperationsPerInvocation(100000)
  def actor_creation_time_for_actorOf_Props_new_EmptyActor__each_time_same__100k_actors() {
    val p = Props(new EmptyActor)
    val propsCreator = () => p

    test(propsCreator)
  }

  @OperationsPerInvocation(100000)
  def actor_creation_time_for_actorOf_Props_classOf_EmptyArgsActor__each_time_new__100k_actors() {
    val propsCreator = () => Props(classOf[EmptyArgsActor], 4711, 1729)

    test(propsCreator)
  }

  @GenerateMicroBenchmark
  @OperationsPerInvocation(100000)
  def actor_creation_time_for_actorOf_Props_classOf_EmptyArgsActor__each_time_same__100k_actors() {
    val p = Props(classOf[EmptyArgsActor], 4711, 1729)
    val propsCreator = () => p

    test(propsCreator)
  }

  @inline def test(propsCreator: () => Props) {
    val driver = system.actorOf(Props(classOf[Driver]), "driver-1")
    probe.watch(driver)

    driver ! IsAlive
    probe.expectMsg(Alive)

    driver ! Create(numberOfActors, propsCreator)
    probe.expectMsgPF(15 seconds, s"waiting for Created") { case Created => }

    driver ! WaitForChildren
    probe.expectMsgPF(15 seconds, s"waiting for Waited") { case Waited => }

    driver ! PoisonPill
    probe.expectTerminated(driver, 15 seconds)
  }

}

object ActorCreationPerfSpec {

  final case class Create(numberOfActors: Int, props: () => Props)
  case object Created
  case object IsAlive
  case object Alive
  case object WaitForChildren
  case object Waited

  class EmptyActor extends Actor {
    def receive = {
      case IsAlive => sender() ! Alive
    }
  }

  class EmptyArgsActor(val foo: Int, val bar: Int) extends Actor {
    def receive = {
      case IsAlive => sender() ! Alive
    }
  }

  class Driver extends Actor {

    def receive = {
      case IsAlive =>
        sender() ! Alive
      case Create(numberOfActors, propsCreator) =>
        for (i ← 1 to numberOfActors) {
          context.actorOf(propsCreator.apply())
        }
        sender() ! Created
      case WaitForChildren =>
        context.children.foreach(_ ! IsAlive)
        context.become(waiting(context.children.size, sender()), discardOld = false)
    }

    def waiting(number: Int, replyTo: ActorRef): Receive = {
      var current = number

      {
        case Alive =>
          current -= 1
          if (current == 0) {
            replyTo ! Waited
            context.unbecome()
          }
      }
    }
  }
}

