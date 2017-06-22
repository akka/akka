/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor

import akka.pattern.ask
import akka.pattern.pipe
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations._
import scala.concurrent.duration._
import java.util.concurrent.TimeUnit
import scala.concurrent.Await
import scala.annotation.tailrec
import java.util.concurrent.CountDownLatch
import akka.actor.SupervisorSpec.PingPongActor
import akka.util.Timeout
import scala.concurrent.Future
import scala.concurrent.forkjoin.ThreadLocalRandom

@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@Fork(1)
@Threads(1)
@Warmup(iterations = 10, time = 5, timeUnit = TimeUnit.SECONDS, batchSize = 1)
@Measurement(iterations = 10, time = 15, timeUnit = TimeUnit.SECONDS, batchSize = 1)
class ActorThroughputBenchmark {
  import ActorThroughputBenchmark._

  @Param(Array("25")) // "5", "25", "50"
  var tpt = 0

  @Param(Array(coresStr))
  var threads = ""

  @Param(Array("SingleConsumerOnlyUnboundedMailbox")) //"default"
  var mailbox = ""

  implicit var system: ActorSystem = _

  @Setup(Level.Trial)
  def setup(): Unit = {

    require(
      Runtime.getRuntime.availableProcessors == cores,
      s"Update the cores constant to ${Runtime.getRuntime.availableProcessors}"
    )

    val mailboxConf = mailbox match {
      case "default" => ""
      case "SingleConsumerOnlyUnboundedMailbox" =>
        s"""default-mailbox.mailbox-type = "${classOf[akka.dispatch.SingleConsumerOnlyUnboundedMailbox].getName}""""
    }

    system = ActorSystem("ActorThroughputBenchmark", ConfigFactory.parseString(
      s"""
        akka {
          log-dead-letters = off
           actor {
             default-dispatcher {
               executor = "fork-join-executor"
               fork-join-executor {
                 parallelism-min = $threads
                 parallelism-factor = 1
                 parallelism-max = $threads
               }
               throughput = $tpt
             }
             $mailboxConf
           }
         }
      """
    ))
  }

  @TearDown(Level.Trial)
  def shutdown(): Unit = {
    system.terminate()
    Await.ready(system.whenTerminated, 15.seconds)
  }

  def startActors(n: Int, props: Props): ActorRef = {
    val parent = system.actorOf(Props[Parent])
    implicit val ec = system.dispatcher
    implicit val askTimeout = Timeout(3.seconds)
    val initDone = parent ? Create(n, props)
    Await.ready(initDone, askTimeout.duration)
    parent
  }

  private def printProgress(totalMessages: Long, numActors: Int, startNanoTime: Long): Unit = {
    val durationMicros = (System.nanoTime() - startNanoTime) / 1000
    println(f"  $totalMessages messages by $numActors actors took ${durationMicros / 1000} ms, " +
      f"${totalMessages.toDouble / durationMicros}%,.2f M msg/s")
  }

  @Benchmark
  @OperationsPerInvocation(totalMessagesTwo)
  def two(): Unit = {
    val latch = new CountDownLatch(twoActors)
    val parent = startActors(twoActors, testProps(totalMessagesTwo / twoActors, latch))
    val startNanoTime = System.nanoTime()
    parent ! Run(2 * tpt)
    latch.await(timeout.toSeconds, TimeUnit.SECONDS)
    printProgress(totalMessagesTwo, twoActors, startNanoTime)
    system.stop(parent)
  }

  @Benchmark
  @OperationsPerInvocation(totalMessagesLessThanCores)
  def lessThanCores(): Unit = {
    val latch = new CountDownLatch(lessThanCoresActors)
    val parent = startActors(lessThanCoresActors, testProps(totalMessagesLessThanCores / lessThanCoresActors, latch))
    val startNanoTime = System.nanoTime()
    parent ! Run(2 * tpt)
    latch.await(timeout.toSeconds, TimeUnit.SECONDS)
    printProgress(totalMessagesLessThanCores, lessThanCoresActors, startNanoTime)
    system.stop(parent)
  }

  @Benchmark
  @OperationsPerInvocation(totalMessagesSameAsCores)
  def sameAsCores(): Unit = {
    val latch = new CountDownLatch(sameAsCoresActors)
    val parent = startActors(sameAsCoresActors, testProps(totalMessagesSameAsCores / sameAsCoresActors, latch))
    val startNanoTime = System.nanoTime()
    parent ! Run(2 * tpt)
    latch.await(timeout.toSeconds, TimeUnit.SECONDS)
    printProgress(totalMessagesSameAsCores, sameAsCoresActors, startNanoTime)
    system.stop(parent)
  }

  @Benchmark
  @OperationsPerInvocation(totalMessagesMoreThanCores)
  def moreThanCores(): Unit = {
    val latch = new CountDownLatch(moreThanCoresActors)
    val parent = startActors(moreThanCoresActors, testProps(totalMessagesMoreThanCores / moreThanCoresActors, latch))
    val startNanoTime = System.nanoTime()
    parent ! Run(2 * tpt)
    latch.await(timeout.toSeconds, TimeUnit.SECONDS)
    printProgress(totalMessagesMoreThanCores, moreThanCoresActors, startNanoTime)
    system.stop(parent)
  }

}

object ActorThroughputBenchmark {

  final val timeout = 30.seconds
  final val messages = 2000000 // messages per actor

  // Constants because they are used in annotations
  // update according to cpu
  final val cores = 8
  final val coresStr = "7"

  final val twoActors = 2
  final val lessThanCoresActors = cores / 2
  final val moreThanCoresActors = cores * 2
  final val sameAsCoresActors = cores
  final val totalMessagesTwo = twoActors * messages
  final val totalMessagesLessThanCores = lessThanCoresActors * messages
  final val totalMessagesMoreThanCores = moreThanCoresActors * messages
  final val totalMessagesSameAsCores = sameAsCoresActors * messages

  final case class Create(n: Int, p: Props)
  final case class Init(all: Array[ActorRef])
  case object InitDone
  final case class Run(initialMessages: Int)
  case object Message

  def testProps(doneAfter: Long, latch: CountDownLatch): Props =
    Props(new TestActor(doneAfter, latch))

  class TestActor(doneAfter: Long, latch: CountDownLatch) extends Actor {
    private var refs: Array[ActorRef] = null
    private var i = 0L
    private var n = 0L

    def receive = {
      case Init(all) =>
        refs = all
        i = refs.indexOf(self)
        sender() ! InitDone
      case Run(initialMessages) =>
        (1 to initialMessages).foreach(_ => send())
        context.become(active)
    }

    def active: Receive = {
      case Message =>
        send()
        n += 1
        if (n == doneAfter)
          latch.countDown()
    }

    private def send(): Unit = {
      i += 1
      refs((i % refs.length).toInt) ! Message
    }
  }

  // use Parent actor to avoid RepointableActorRef, and more clean initialization
  class Parent extends Actor {

    def receive = {
      case Create(n, p) =>
        val refs = (1 to n).map(_ => context.actorOf(p)).toVector
        // all must receive the refs first
        val init = Init(refs.toArray)
        import context.dispatcher
        implicit val askTimeout = Timeout(3.seconds)
        Future.sequence(refs.map((_ ? init))).map(_ => InitDone).pipeTo(sender())
        context.become(active(refs))
    }

    def active(refs: Seq[ActorRef]): Receive = {
      case r: Run => refs.foreach(_ ! r)
    }

  }
}
