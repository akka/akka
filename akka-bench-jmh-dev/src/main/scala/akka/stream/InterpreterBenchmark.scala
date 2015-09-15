package akka.stream

import akka.event._
import akka.stream.impl.fusing.{ InterpreterSpecKit, GraphInterpreterSpec, GraphStages, Map => MapStage, OneBoundedInterpreter }
import akka.stream.impl.fusing.GraphStages.Identity
import akka.stream.impl.fusing.GraphInterpreter.{ DownstreamBoundaryStageLogic, UpstreamBoundaryStageLogic }
import akka.stream.stage._
import org.openjdk.jmh.annotations._

import scala.concurrent.Lock

import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class InterpreterBenchmark {
  import InterpreterBenchmark._

  // manual, and not via @Param, because we want @OperationsPerInvocation on our tests
  final val data100k = (1 to 100000).toVector

  @Param(Array("1", "5", "10"))
  val numberOfIds = 0

  @Benchmark
  @OperationsPerInvocation(100000)
  def graph_interpreter_100k_elements() {
    val lock = new Lock()
    lock.acquire()
    new GraphInterpreterSpec.TestSetup() {
      val identities = Vector.fill(numberOfIds)(new Identity[Int])
      val source = new GraphDataSource("source", data100k)
      val sink = new GraphDataSink[Int]("sink", data100k.size, lock)

      val b = builder(identities:_*)
        .connect(source, identities.head.in)
        .connect(identities.last.out, sink)

      for (i <- (0 until identities.size - 1)) {
        b.connect(identities(i).out, identities(i + 1).in)
      }

      b.init()
      sink.requestOne()
      interpreter.execute(Int.MaxValue)
    }
    lock.acquire()
  }

  @Benchmark
  @OperationsPerInvocation(100000)
  def onebounded_interpreter_100k_elements() {
    val lock = new Lock()
    lock.acquire()
    val sink = OneBoundedDataSink(data100k.size, lock)
    val ops = Vector.fill(numberOfIds)(MapStage(identity[Int], Supervision.stoppingDecider))
    val interpreter = new OneBoundedInterpreter(OneBoundedDataSource(data100k) +: ops :+ sink,
      (op, ctx, event) ⇒ (),
      Logging(NoopBus, classOf[InterpreterBenchmark]),
      null,
      Attributes.none,
      forkLimit = 100, overflowToHeap = false)
    interpreter.init()
    sink.requestOne()
    lock.acquire()
  }
}

object InterpreterBenchmark {

  case class GraphDataSource[T](override val toString: String, data: Vector[T]) extends UpstreamBoundaryStageLogic[T] {
    var idx = 0
    val out = Outlet[T]("out")

    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        if (idx < data.size) {
          push(out, data(idx))
          idx += 1
        }
        else {
          completeStage()
        }
      }
      override def onDownstreamFinish(): Unit = completeStage()
    })
  }

  case class GraphDataSink[T](override val toString: String, var expected: Int, completionLock: Lock) extends DownstreamBoundaryStageLogic[T] {
    val in = Inlet[T]("in")

    setHandler(in, new InHandler {
      override def onPush(): Unit = {
        expected -= 1
        pull(in)
        if (expected == 0) {
          completionLock.release()
        }
      }
      override def onUpstreamFinish(): Unit = completeStage()
      override def onUpstreamFailure(ex: Throwable): Unit = failStage(ex)
    })

    def requestOne() = pull(in)
  }

  case class OneBoundedDataSource[T](data: Vector[T]) extends BoundaryStage {
    var idx = 0

    override def onDownstreamFinish(ctx: BoundaryContext): TerminationDirective = {
      ctx.finish()
    }

    override def onPull(ctx: BoundaryContext): Directive = {
      if (idx < data.size) {
          idx += 1
          ctx.push(data(idx - 1))
        }
        else {
          ctx.finish()
        }
    }

    override def onPush(elem: Any, ctx: BoundaryContext): Directive =
      throw new UnsupportedOperationException("Cannot push the boundary")
  }

  case class OneBoundedDataSink(var expected: Int, completionLock: Lock) extends BoundaryStage {
    override def onPush(elem: Any, ctx: BoundaryContext): Directive = {
      expected -= 1
      if (expected == 0) {
        completionLock.release()
      }
      ctx.pull()
    }

    override def onUpstreamFinish(ctx: BoundaryContext): TerminationDirective = {
      ctx.finish()
    }

    override def onUpstreamFailure(cause: Throwable, ctx: BoundaryContext): TerminationDirective = {
      ctx.finish()
    }

    override def onPull(ctx: BoundaryContext): Directive =
      throw new UnsupportedOperationException("Cannot pull the boundary")

    def requestOne(): Unit = enterAndPull()
  }

  val NoopBus = new LoggingBus {
    override def subscribe(subscriber: Subscriber, to: Classifier): Boolean = true
    override def publish(event: Event): Unit = ()
    override def unsubscribe(subscriber: Subscriber, from: Classifier): Boolean = true
    override def unsubscribe(subscriber: Subscriber): Unit = ()
  }
}
