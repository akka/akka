/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl

import akka.stream._
import akka.stream.scaladsl._
import akka.stream.testkit.StreamSpec
import org.reactivestreams.{ Publisher, Subscriber, Subscription }
import org.scalatest.concurrent.PatienceConfiguration.Timeout

import scala.concurrent.duration._

class StreamLayoutSpec extends StreamSpec {
  import StreamLayout._

  def testAtomic(inPortCount: Int, outPortCount: Int): Module = new AtomicModule {
    override val shape = AmorphousShape(List.fill(inPortCount)(Inlet("")), List.fill(outPortCount)(Outlet("")))
    override def replaceShape(s: Shape): Module = ???

    override def carbonCopy: Module = ???

    override def attributes: Attributes = Attributes.none
    override def withAttributes(attributes: Attributes): Module = this
  }

  def testStage(): Module = testAtomic(1, 1)
  def testSource(): Module = testAtomic(0, 1)
  def testSink(): Module = testAtomic(1, 0)

  implicit val materializer = ActorMaterializer(ActorMaterializerSettings(system).withAutoFusing(false))

  "StreamLayout" must {

    "be able to model simple linear stages" in {
      val stage1 = testStage()

      stage1.inPorts.size should be(1)
      stage1.outPorts.size should be(1)
      stage1.isRunnable should be(false)
      stage1.isFlow should be(true)
      stage1.isSink should be(false)
      stage1.isSource should be(false)

      val stage2 = testStage()
      val flow12 = stage1.compose(stage2, Keep.none).wire(stage1.outPorts.head, stage2.inPorts.head)

      flow12.inPorts should be(stage1.inPorts)
      flow12.outPorts should be(stage2.outPorts)
      flow12.isRunnable should be(false)
      flow12.isFlow should be(true)
      flow12.isSink should be(false)
      flow12.isSource should be(false)

      val source0 = testSource()
      source0.inPorts.size should be(0)
      source0.outPorts.size should be(1)
      source0.isRunnable should be(false)
      source0.isFlow should be(false)
      source0.isSink should be(false)
      source0.isSource should be(true)

      val sink3 = testSink()
      sink3.inPorts.size should be(1)
      sink3.outPorts.size should be(0)
      sink3.isRunnable should be(false)
      sink3.isFlow should be(false)
      sink3.isSink should be(true)
      sink3.isSource should be(false)

      val source012 = source0.compose(flow12, Keep.none).wire(source0.outPorts.head, flow12.inPorts.head)
      source012.inPorts.size should be(0)
      source012.outPorts should be(flow12.outPorts)
      source012.isRunnable should be(false)
      source012.isFlow should be(false)
      source012.isSink should be(false)
      source012.isSource should be(true)

      val sink123 = flow12.compose(sink3, Keep.none).wire(flow12.outPorts.head, sink3.inPorts.head)
      sink123.inPorts should be(flow12.inPorts)
      sink123.outPorts.size should be(0)
      sink123.isRunnable should be(false)
      sink123.isFlow should be(false)
      sink123.isSink should be(true)
      sink123.isSource should be(false)

      val runnable0123a = source0.compose(sink123, Keep.none).wire(source0.outPorts.head, sink123.inPorts.head)
      val runnable0123b = source012.compose(sink3, Keep.none).wire(source012.outPorts.head, sink3.inPorts.head)

      val runnable0123c =
        source0
          .compose(flow12, Keep.none).wire(source0.outPorts.head, flow12.inPorts.head)
          .compose(sink3, Keep.none).wire(flow12.outPorts.head, sink3.inPorts.head)

      runnable0123a.inPorts.size should be(0)
      runnable0123a.outPorts.size should be(0)
      runnable0123a.isRunnable should be(true)
      runnable0123a.isFlow should be(false)
      runnable0123a.isSink should be(false)
      runnable0123a.isSource should be(false)
    }

    "be able to materialize linear layouts" in {
      val source = testSource()
      val stage1 = testStage()
      val stage2 = testStage()
      val sink = testSink()

      val runnable = source.compose(stage1, Keep.none).wire(source.outPorts.head, stage1.inPorts.head)
        .compose(stage2, Keep.none).wire(stage1.outPorts.head, stage2.inPorts.head)
        .compose(sink, Keep.none).wire(stage2.outPorts.head, sink.inPorts.head)

      checkMaterialized(runnable)
    }

    val tooDeepForStack = 50000

    "fail fusing when value computation is too complex" in {
      // this tests that the canary in to coal mine actually works
      val g = (1 to tooDeepForStack)
        .foldLeft(Flow[Int].mapMaterializedValue(_ ⇒ 1)) { (flow, i) ⇒
          flow.mapMaterializedValue(x ⇒ x + i)
        }
      a[StackOverflowError] shouldBe thrownBy {
        Fusing.aggressive(g)
      }
    }

    // Seen tests run in 9-10 seconds, these test cases are heavy on the GC
    val veryPatient = Timeout(20.seconds)

    "not fail materialization when building a large graph with simple computation" when {

      "starting from a Source" in {
        val g = (1 to tooDeepForStack)
          .foldLeft(Source.single(42).mapMaterializedValue(_ ⇒ 1))(
            (f, i) ⇒ f.map(identity))
        val (mat, fut) = g.toMat(Sink.seq)(Keep.both).run()
        mat should ===(1)
        fut.futureValue(veryPatient) should ===(List(42))
      }

      "starting from a Flow" in {
        val g = (1 to tooDeepForStack).foldLeft(Flow[Int])((f, i) ⇒ f.map(identity))
        val (mat, fut) = g.runWith(Source.single(42).mapMaterializedValue(_ ⇒ 1), Sink.seq)
        mat should ===(1)
        fut.futureValue(veryPatient) should ===(List(42))
      }

      "using .via" in {
        val g = (1 to tooDeepForStack)
          .foldLeft(Source.single(42).mapMaterializedValue(_ ⇒ 1))(
            (f, i) ⇒ f.via(Flow[Int].map(identity)))
        val (mat, fut) = g.toMat(Sink.seq)(Keep.both).run()
        mat should ===(1)
        fut.futureValue(veryPatient) should ===(List(42))
      }
    }

    "not fail fusing & materialization when building a large graph with simple computation" when {

      "starting from a Source" in {
        val g = Source fromGraph Fusing.aggressive((1 to tooDeepForStack)
          .foldLeft(Source.single(42).mapMaterializedValue(_ ⇒ 1))(
            (f, i) ⇒ f.map(identity)))
        val (mat, fut) = g.toMat(Sink.seq)(Keep.both).run()
        mat should ===(1)
        fut.futureValue(veryPatient) should ===(List(42))
      }

      "starting from a Flow" in {
        val g = Flow fromGraph Fusing.aggressive((1 to tooDeepForStack).foldLeft(Flow[Int])((f, i) ⇒ f.map(identity)))
        val (mat, fut) = g.runWith(Source.single(42).mapMaterializedValue(_ ⇒ 1), Sink.seq)
        mat should ===(1)
        fut.futureValue(veryPatient) should ===(List(42))
      }

      "using .via" in {
        val g = Source fromGraph Fusing.aggressive((1 to tooDeepForStack)
          .foldLeft(Source.single(42).mapMaterializedValue(_ ⇒ 1))(
            (f, i) ⇒ f.via(Flow[Int].map(identity))))
        val (mat, fut) = g.toMat(Sink.seq)(Keep.both).run()
        mat should ===(1)
        fut.futureValue(veryPatient) should ===(List(42))
      }
    }

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
    var publishers = Vector.empty[TestPublisher]
    var subscribers = Vector.empty[TestSubscriber]

    override protected def materializeAtomic(atomic: AtomicModule, effectiveAttributes: Attributes,
                                             matVal: java.util.Map[Module, Any]): Unit = {
      for (inPort ← atomic.inPorts) {
        val subscriber = TestSubscriber(atomic, inPort)
        subscribers :+= subscriber
        assignPort(inPort, subscriber)
      }
      for (outPort ← atomic.outPorts) {
        val publisher = TestPublisher(atomic, outPort)
        publishers :+= publisher
        assignPort(outPort, publisher)
      }
    }
  }

  def checkMaterialized(topLevel: Module): (Set[TestPublisher], Set[TestSubscriber]) = {
    val materializer = new FlatTestMaterializer(topLevel)
    materializer.materialize()
    materializer.publishers.isEmpty should be(false)
    materializer.subscribers.isEmpty should be(false)

    materializer.subscribers.size should be(materializer.publishers.size)

    val inToSubscriber: Map[InPort, TestSubscriber] = materializer.subscribers.map(s ⇒ s.port → s).toMap
    val outToPublisher: Map[OutPort, TestPublisher] = materializer.publishers.map(s ⇒ s.port → s).toMap

    for (publisher ← materializer.publishers) {
      publisher.owner.isAtomic should be(true)
      topLevel.upstreams(publisher.downstreamPort) should be(publisher.port)
    }

    for (subscriber ← materializer.subscribers) {
      subscriber.owner.isAtomic should be(true)
      topLevel.downstreams(subscriber.upstreamPort) should be(subscriber.port)
    }

    def getAllAtomic(module: Module): Set[Module] = {
      val (atomics, composites) = module.subModules.partition(_.isAtomic)
      atomics ++ composites.flatMap(getAllAtomic)
    }

    val allAtomic = getAllAtomic(topLevel)

    for (atomic ← allAtomic) {
      for (in ← atomic.inPorts; subscriber = inToSubscriber(in)) {
        subscriber.owner should be(atomic)
        subscriber.upstreamPort should be(topLevel.upstreams(in))
        subscriber.upstreamModule.outPorts.exists(outToPublisher(_).downstreamPort == in)
      }
      for (out ← atomic.outPorts; publisher = outToPublisher(out)) {
        publisher.owner should be(atomic)
        publisher.downstreamPort should be(topLevel.downstreams(out))
        publisher.downstreamModule.inPorts.exists(inToSubscriber(_).upstreamPort == out)
      }
    }

    materializer.publishers.distinct.size should be(materializer.publishers.size)
    materializer.subscribers.distinct.size should be(materializer.subscribers.size)

    (materializer.publishers.toSet, materializer.subscribers.toSet)
  }

}
