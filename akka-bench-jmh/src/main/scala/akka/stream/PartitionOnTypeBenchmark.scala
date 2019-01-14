/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import java.util.concurrent.TimeUnit

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.{ Broadcast, Flow, GraphDSL, PartitionOnType, Sink, Source }
import org.openjdk.jmh.annotations.{ OperationsPerInvocation, _ }

import scala.collection.immutable
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

object PartitionOnTypeBenchmark {
  final val OperationsPerInvocation = 10000
}

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class PartitionOnTypeBenchmark {

  import PartitionOnTypeBenchmark.OperationsPerInvocation

  implicit val system = ActorSystem("PartitionOnTypeBenchmark")

  var materializer: ActorMaterializer = _

  @Param(Array("2", "5")) // note that these has specific support below, 5 is arbitrary but 2 has special optimization
  var NumberOfPartitions = 0

  sealed trait SuperType
  case object Type1 extends SuperType
  case object Type2 extends SuperType
  case object Type3 extends SuperType
  case object Type4 extends SuperType
  case object Type5 extends SuperType

  var testSource: Source[SuperType, NotUsed] = _

  var testSink: Sink[SuperType, List[Future[SuperType]]] = _

  var testReferenceSink: Sink[SuperType, List[Future[SuperType]]] = _

  @Setup
  def setup(): Unit = {
    materializer = ActorMaterializer()

    val elemsF = NumberOfPartitions match {
      case 2 ⇒ () ⇒ Iterator[SuperType](Type1, Type2)
      case 5 ⇒ () ⇒ Iterator[SuperType](Type1, Type2, Type3, Type4, Type5)
    }
    testSource = Source.cycle(elemsF).take(OperationsPerInvocation)

    testSink = (NumberOfPartitions match {
      case 2 ⇒
        PartitionOnType[SuperType]()
          .addSink(Sink.last[Type1.type])
          .addSink(Sink.last[Type2.type])
          .build()
      case 5 ⇒
        PartitionOnType[SuperType]()
          .addSink(Sink.last[Type1.type])
          .addSink(Sink.last[Type2.type])
          .addSink(Sink.last[Type3.type])
          .addSink(Sink.last[Type4.type])
          .addSink(Sink.last[Type5.type])
          .build()
    }).mapMaterializedValue(_.asInstanceOf[immutable.Seq[Future[SuperType]]].toList)

    // optimal hand written partition on type with broadcast + filter
    testReferenceSink = {

      NumberOfPartitions match {
        case 2 ⇒
          val sinks = Sink.head[SuperType] :: Sink.head[SuperType] :: Nil
          Sink.fromGraph(GraphDSL.create(sinks) { implicit b ⇒ sinks ⇒
            import GraphDSL.Implicits._

            val bc = b.add(Broadcast[SuperType](NumberOfPartitions, false))

            bc.out(0) ~> b.add(Flow[SuperType].filter(_.isInstanceOf[Type1.type])) ~> sinks(0)
            bc.out(1) ~> b.add(Flow[SuperType].filter(_.isInstanceOf[Type2.type])) ~> sinks(1)

            SinkShape(bc.in)
          }).mapMaterializedValue(_.toList)

        case 5 ⇒
          val sinks = Sink.head[SuperType] :: Sink.head[SuperType] :: Sink.head[SuperType] :: Sink.head[SuperType] :: Sink.head[SuperType] :: Nil
          Sink.fromGraph(GraphDSL.create(sinks) { implicit b ⇒ sinks ⇒
            import GraphDSL.Implicits._

            val bc = b.add(Broadcast[SuperType](NumberOfPartitions, false))

            bc.out(0) ~> b.add(Flow[SuperType].filter(_.isInstanceOf[Type1.type])) ~> sinks(0)
            bc.out(1) ~> b.add(Flow[SuperType].filter(_.isInstanceOf[Type2.type])) ~> sinks(1)
            bc.out(2) ~> b.add(Flow[SuperType].filter(_.isInstanceOf[Type3.type])) ~> sinks(2)
            bc.out(3) ~> b.add(Flow[SuperType].filter(_.isInstanceOf[Type4.type])) ~> sinks(3)
            bc.out(4) ~> b.add(Flow[SuperType].filter(_.isInstanceOf[Type5.type])) ~> sinks(4)

            SinkShape(bc.in)
          }).mapMaterializedValue(_.toList)

      }

    }
  }

  @TearDown
  def shutdown(): Unit = {
    Await.result(system.terminate(), 5.seconds)
  }

  @Benchmark
  @OperationsPerInvocation(OperationsPerInvocation)
  def partition(): Unit = {
    import system.dispatcher
    val matVals = testSource.runWith(testSink)(materializer)
    Await.result(Future.sequence(matVals), 2.minutes)
  }

  /*
akka-bench-jmh/jmh:run -wi 3 -i 5 -f 1 .*PartitionOnTypeBenchmark.*

   [info] Benchmark                                         (NumberOfPartitions)   Mode  Cnt          Score         Error  Units
[info] PartitionOnTypeBenchmark.broadcastFilterBaseline                     2  thrpt    5  331174964.692 ± 6652987.726  ops/s
[info] PartitionOnTypeBenchmark.broadcastFilterBaseline                     5  thrpt    5  249203038.004 ±  786789.223  ops/s
[info] PartitionOnTypeBenchmark.partition                                   2  thrpt    5    4232221.992 ±   71334.024  ops/s
[info] PartitionOnTypeBenchmark.partition                                   5  thrpt    5    3984340.563 ± 2093098.121  ops/s

  @Benchmark
  @OperationsPerInvocation(OperationsPerInvocation)
  def broadcastFilterBaseline(): Unit = {
    import system.dispatcher
    val matVals = testSource.runWith(testReferenceSink)(materializer)
    Await.result(Future.sequence(matVals), 2.minutes)
  }
  */

}
