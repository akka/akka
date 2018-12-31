/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

/*
import java.util
import java.util.concurrent.TimeUnit

import akka.stream.impl.MaterializerSession
import akka.stream.impl.NewLayout._
import akka.stream.impl.StreamLayout.{ AtomicModule, Module }
import org.openjdk.jmh.annotations._
import org.reactivestreams.{ Publisher, Subscriber, Subscription }

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class NewLayoutBenchmark {

  // TODO: This benchmark is heavily copy-pasta. This is a temporary benchmark as these two implementations
  // will never exist at the same time. This needs to be turned into a better one once the design
  // settles.

  // --- These test classes do not use the optimized linear builder, for testing the composite builder instead
  class CompositeTestSource extends AtomicModule {
    val out = Outlet[Any]("testSourceC.out")
    override val shape: Shape = SourceShape(out)
    override val attributes: Attributes = Attributes.name("testSource")
    val traversalBuilder = TraversalBuilder.atomic(this)

    override def withAttributes(attributes: Attributes): Module = ???
    override def carbonCopy: Module = ???
    override def replaceShape(s: Shape): Module = ???
    override def toString = "TestSource"
  }

  class CompositeTestSink extends AtomicModule {
    val in = Inlet[Any]("testSinkC.in")
    override val shape: Shape = SinkShape(in)
    override val attributes: Attributes = Attributes.name("testSink")
    val traversalBuilder = TraversalBuilder.atomic(this)

    override def withAttributes(attributes: Attributes): Module = ???
    override def carbonCopy: Module = ???
    override def replaceShape(s: Shape): Module = ???
    override def toString = "TestSink"
  }

  class CompositeTestFlow(tag: String) extends AtomicModule {
    val in = Inlet[Any](s"testFlowC$tag.in")
    val out = Outlet[Any](s"testFlowC$tag.out")
    override val shape: Shape = FlowShape(in, out)
    override val attributes: Attributes = Attributes.name(s"testFlow$tag")
    val traversalBuilder = TraversalBuilder.atomic(this)

    override def withAttributes(attributes: Attributes): Module = ???
    override def carbonCopy: Module = ???
    override def replaceShape(s: Shape): Module = ???
    override def toString = s"TestFlow$tag"
  }

  // --- These test classes DO use the optimized linear builder, for testing the composite builder instead
  class LinearTestSource extends AtomicModule {
    val out = Outlet[Any]("testSource.out")
    override val shape: Shape = SourceShape(out)
    override val attributes: Attributes = Attributes.name("testSource")
    val traversalBuilder = TraversalBuilder.linear(this)

    override def withAttributes(attributes: Attributes): Module = ???
    override def carbonCopy: Module = ???
    override def replaceShape(s: Shape): Module = ???
    override def toString = "TestSource"
  }

  class LinearTestSink extends AtomicModule {
    val in = Inlet[Any]("testSink.in")
    override val shape: Shape = SinkShape(in)
    override val attributes: Attributes = Attributes.name("testSink")
    val traversalBuilder = TraversalBuilder.linear(this)

    override def withAttributes(attributes: Attributes): Module = ???
    override def carbonCopy: Module = ???
    override def replaceShape(s: Shape): Module = ???
    override def toString = "TestSink"
  }

  class LinearTestFlow(tag: String) extends AtomicModule {
    val in = Inlet[Any](s"testFlow$tag.in")
    val out = Outlet[Any](s"testFlow$tag.out")
    override val shape: Shape = FlowShape(in, out)
    override val attributes: Attributes = Attributes.name(s"testFlow$tag")
    val traversalBuilder = TraversalBuilder.linear(this)

    override def withAttributes(attributes: Attributes): Module = ???
    override def carbonCopy: Module = ???
    override def replaceShape(s: Shape): Module = ???
    override def toString = s"TestFlow$tag"
  }

  class MaterializationResult(
      val connections: Int,
      val inlets: Array[InPort],
      val outlets: Array[OutPort]
  ) {

    override def toString = {
      outlets.iterator.zip(inlets.iterator).mkString("connections: ", ", ", "")
    }
  }

  class OldSource extends AtomicModule {
    val out = Outlet[Any]("out")
    override val shape = SourceShape(out)

    override def replaceShape(s: Shape): Module = ???

    override def carbonCopy: Module = ???

    override def attributes: Attributes = Attributes.none
    override def withAttributes(attributes: Attributes): Module = this
  }

  class OldSink extends AtomicModule {
    val in = Inlet[Any]("in")
    override val shape = SinkShape(in)

    override def replaceShape(s: Shape): Module = ???

    override def carbonCopy: Module = ???

    override def attributes: Attributes = Attributes.none
    override def withAttributes(attributes: Attributes): Module = this
  }

  class OldFlow extends AtomicModule {
    val in = Inlet[Any]("in")
    val out = Outlet[Any]("out")
    override val shape = FlowShape(in, out)

    override def replaceShape(s: Shape): Module = ???

    override def carbonCopy: Module = ???

    override def attributes: Attributes = Attributes.none
    override def withAttributes(attributes: Attributes): Module = this
  }

  val linearSource = new LinearTestSource
  val linearSink = new LinearTestSink
  val linearFlow = new LinearTestFlow("linearFlow")

  val compositeSource = new CompositeTestSource
  val compositeSink = new CompositeTestSink
  val compositeFlow = new CompositeTestFlow("linearFlow")

  val oldSource = new OldSource
  val oldSink = new OldSink
  val oldFlow = new OldFlow

  def testMaterializeNew(b: TraversalBuilder): MaterializationResult = {
    require(b.isTraversalComplete, "Traversal builder must be complete")

    val connections = b.inSlots
    val inlets = Array.ofDim[InPort](connections)
    val outlets = Array.ofDim[OutPort](connections)

    // Track next assignable number for input ports
    var inOffs = 0

    var current: Traversal = b.traversal.get
    val traversalStack = new util.ArrayList[Traversal](16)
    traversalStack.add(current)

    // Due to how Concat works, we need a stack. This probably can be optimized for the most common cases.
    while (!traversalStack.isEmpty) {
      current = traversalStack.remove(traversalStack.size() - 1)

      while (current ne EmptyTraversal) {
        current match {
          case MaterializeAtomic(mod, outToSlot) ⇒
            var i = 0
            val inletsIter = mod.shape.inlets.iterator
            while (inletsIter.hasNext) {
              val in = inletsIter.next()
              inlets(inOffs + i) = in
              i += 1
            }

            val outletsIter = mod.shape.outlets.iterator
            while (outletsIter.hasNext) {
              val out = outletsIter.next()
              outlets(inOffs + outToSlot(out.id)) = out
            }
            inOffs += mod.shape.inlets.size
            current = current.next
          // And that's it ;)
          case Concat(first, next) ⇒
            traversalStack.add(next)
            current = first
          case _ ⇒
            current = current.next
        }
      }
    }
    new MaterializationResult(connections, inlets, outlets)
  }

  case class TestPublisher(owner: Module, port: OutPort) extends Publisher[Any] with Subscription {
    var downstreamModule: Module = _
    var downstreamPort: InPort = _

    override def subscribe(s: Subscriber[_ >: Any]): Unit = s match {
      case TestSubscriber(o, p) ⇒
        downstreamModule = o
        downstreamPort = p
        s.onSubscribe(this)
    }

    override def request(n: Long): Unit = ()
    override def cancel(): Unit = ()
  }

  case class TestSubscriber(owner: Module, port: InPort) extends Subscriber[Any] {
    var upstreamModule: Module = _
    var upstreamPort: OutPort = _

    override def onSubscribe(s: Subscription): Unit = s match {
      case TestPublisher(o, p) ⇒
        upstreamModule = o
        upstreamPort = p
    }

    override def onError(t: Throwable): Unit = ()
    override def onComplete(): Unit = ()
    override def onNext(t: Any): Unit = ()
  }

  class FlatTestMaterializer(_module: Module) extends MaterializerSession(_module, Attributes()) {
    private var i = 0
    var publishers = Array.ofDim[TestPublisher](1024)
    var subscribers = Array.ofDim[TestSubscriber](1024)

    override protected def materializeAtomic(atomic: AtomicModule, effectiveAttributes: Attributes,
      matVal: java.util.Map[Module, Any]): Unit = {
      for (inPort ← atomic.inPorts) {
        val subscriber = TestSubscriber(atomic, inPort)
        subscribers(i) = subscriber
        i += 1
        assignPort(inPort, subscriber)
      }
      for (outPort ← atomic.outPorts) {
        val publisher = TestPublisher(atomic, outPort)
        publishers(i) = publisher
        i += 1
        assignPort(outPort, publisher)
      }
    }
  }

  def testMaterializeOld(m: Module, blackhole: org.openjdk.jmh.infra.Blackhole): Unit = {
    val mat = new FlatTestMaterializer(m)
    mat.materialize()
    blackhole.consume(mat.publishers)
  }

  @Benchmark
  def source_and_sink_new_linear(): TraversalBuilder = {
    linearSource.traversalBuilder.append(linearSink.traversalBuilder, linearSink.shape)
  }

  @Benchmark
  def source_and_sink_new_composite(): TraversalBuilder = {
    compositeSource.traversalBuilder
      .add(compositeSink.traversalBuilder, compositeSink.shape)
      .wire(compositeSource.out, compositeSink.in)
  }

  @Benchmark
  def source_and_sink_old(): Module = {
    oldSource
      .compose(oldSink)
      .wire(oldSource.out, oldSink.in)
  }

  @Benchmark
  def source_flow_and_sink_new_linear(): TraversalBuilder = {
    linearSource.traversalBuilder
      .append(linearFlow.traversalBuilder, linearFlow.shape)
      .append(linearSink.traversalBuilder, linearSink.shape)
  }

  @Benchmark
  def source_flow_and_sink_new_composite(): TraversalBuilder = {
    compositeSource.traversalBuilder
      .add(compositeFlow.traversalBuilder, compositeFlow.shape)
      .wire(compositeSource.out, compositeFlow.in)
      .add(compositeSink.traversalBuilder, compositeSink.shape)
      .wire(compositeFlow.out, compositeSink.in)
  }

  @Benchmark
  def source_flow_and_sink_old(): Module = {
    oldSource
      .compose(oldFlow)
      .wire(oldSource.out, oldFlow.in)
      .compose(oldSink)
      .wire(oldFlow.out, oldSink.in)
  }

  val sourceSinkLinear = linearSource.traversalBuilder.append(linearSink.traversalBuilder, linearSink.shape)
  val sourceSinkComposite = compositeSource.traversalBuilder
    .add(compositeSink.traversalBuilder, compositeSink.shape)
    .wire(compositeSource.out, compositeSink.in)
  val sourceSinkOld = oldSource
    .compose(oldSink)
    .wire(oldSource.out, oldSink.in)

  val sourceFlowSinkLinear = linearSource.traversalBuilder
    .append(linearFlow.traversalBuilder, linearFlow.shape)
    .append(linearSink.traversalBuilder, linearSink.shape)
  val sourceFlowSinkComposite = compositeSource.traversalBuilder
    .add(compositeFlow.traversalBuilder, compositeFlow.shape)
    .wire(compositeSource.out, compositeFlow.in)
    .add(compositeSink.traversalBuilder, compositeSink.shape)
    .wire(compositeFlow.out, compositeSink.in)
  val sourceFlowSinkOld = oldSource
    .compose(oldFlow)
    .wire(oldSource.out, oldFlow.in)
    .compose(oldSink)
    .wire(oldFlow.out, oldSink.in)

  @Benchmark
  def mat_source_and_sink_new_linear(): MaterializationResult = {
    testMaterializeNew(sourceSinkLinear)
  }

  @Benchmark
  def mat_source_and_sink_new_composite(): MaterializationResult = {
    testMaterializeNew(sourceSinkComposite)
  }

  @Benchmark
  def mat_source_and_sink_old(blackhole: org.openjdk.jmh.infra.Blackhole): Unit = {
    testMaterializeOld(sourceSinkOld, blackhole: org.openjdk.jmh.infra.Blackhole)
  }

  @Benchmark
  def mat_source_flow_and_sink_new_linear(): MaterializationResult = {
    testMaterializeNew(sourceFlowSinkLinear)
  }

  @Benchmark
  def mat_source_flow_and_sink_new_composite(): MaterializationResult = {
    testMaterializeNew(sourceFlowSinkComposite)
  }

  @Benchmark
  def mat_source_flow_and_sink_old(blackhole: org.openjdk.jmh.infra.Blackhole): Unit = {
    testMaterializeOld(sourceFlowSinkOld, blackhole: org.openjdk.jmh.infra.Blackhole)
  }

}
*/
