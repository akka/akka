/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.stream

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.ActorSystem
import akka.stream.impl._
import akka.stream.scaladsl.{ Keep, RunnableGraph, Sink, Source }
import akka.stream.stage.{ GraphStageLogic, GraphStageWithMaterializedValue, InHandler, OutHandler }
import org.openjdk.jmh.annotations._

import scala.concurrent.Await
import scala.concurrent.duration._

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class SpecialEmptyMaterializerBenchmark {

  implicit val system = ActorSystem("FastOnlyMatMaterializerBenchmark")
  val materializer = ActorMaterializer()(system)
  val fastSpecialMat = SpecialEmptyMaterializer(system, materializer.settings, system.dispatchers, system.deadLetters, new AtomicBoolean(false), SeqActorName("FAST"))

  var playEmptyGraph: RunnableGraph[String] = _

  /*
    BEFORE:
    [info] SpecialEmptyMaterializerBenchmark.materialize_play_empty_NORMAL   thrpt    20   91 088.329 ± 16 935.338  ops/s
    SPECIAL:
    [info] SpecialEmptyMaterializerBenchmark.materialize_play_empty_SPECIAL  thrpt    20  395 775.689 ± 31 776.455  ops/s
    [info] SpecialEmptyMaterializerBenchmark.materialize_play_empty_SPECIAL  thrpt    20  654 223.670 ± 17 116.629  ops/s
   */

  @Setup
  def setup(): Unit = {
    playEmptyGraph =
      Source.empty[Unit]
        .fold("") { case _ => "" + "" }
        .viaMat(new GraphStageWithMaterializedValue[FlowShape[String, String], String] {
          val in = Inlet[String]("in")
          val out = Outlet[String]("out")
          override val shape: FlowShape[String, String] = FlowShape(in, out)

          override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) =
            new GraphStageLogic(shape) with InHandler with OutHandler {
              setHandlers(in, out, this)

              override def onPush(): Unit = push(out, grab(in))
              override def onPull(): Unit = pull(in)
            } -> "MAT"
        })(Keep.right)
        .to(Sink.head)
  }

  @TearDown
  def shutdown(): Unit = {
    Await.result(system.terminate(), 5.seconds)
  }

  @Benchmark
  def materialize_play_empty_SPECIAL: String = {
    playEmptyGraph.run()(fastSpecialMat)
  }

  @Benchmark
  def materialize_play_empty_NORMAL: String = {
    playEmptyGraph.run()(materializer)
  }
}

