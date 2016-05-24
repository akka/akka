/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl

import akka.event.LoggingAdapter
import akka.stream.ActorAttributes.SupervisionStrategy
import akka.stream.Attributes._
import akka.stream.Supervision.Decider
import akka.stream._
import akka.stream.impl.StreamLayout._
import akka.stream.stage.AbstractStage.PushPullGraphStage
import akka.stream.stage.Stage
import org.reactivestreams.Processor

import scala.collection.immutable

/**
 * INTERNAL API
 */
private[stream] object Stages {

  object DefaultAttributes {
    val IODispatcher = ActorAttributes.Dispatcher("akka.stream.default-blocking-io-dispatcher")
    val inputBufferOne = inputBuffer(initial = 1, max = 1)

    val fused = name("fused")
    val materializedValueSource = name("matValueSource")
    val map = name("map")
    val log = name("log")
    val filter = name("filter")
    val filterNot = name("filterNot")
    val collect = name("collect")
    val recover = name("recover")
    val mapAsync = name("mapAsync")
    val mapAsyncUnordered = name("mapAsyncUnordered")
    val grouped = name("grouped")
    val groupedWithin = name("groupedWithin")
    val limit = name("limit")
    val limitWeighted = name("limitWeighted")
    val sliding = name("sliding")
    val take = name("take")
    val drop = name("drop")
    val takeWhile = name("takeWhile")
    val dropWhile = name("dropWhile")
    val scan = name("scan")
    val fold = name("fold")
    val reduce = name("reduce")
    val intersperse = name("intersperse")
    val buffer = name("buffer")
    val conflate = name("conflate")
    val batch = name("batch")
    val batchWeighted = name("batchWeighted")
    val expand = name("expand")
    val statefulMapConcat = name("statefulMapConcat")
    val detacher = name("detacher")
    val groupBy = name("groupBy")
    val prefixAndTail = name("prefixAndTail")
    val split = name("split")
    val concatAll = name("concatAll")
    val processor = name("processor")
    val processorWithKey = name("processorWithKey")
    val identityOp = name("identityOp")
    val delimiterFraming = name("delimiterFraming")

    val initial = name("initial")
    val completion = name("completion")
    val idle = name("idle")
    val idleTimeoutBidi = name("idleTimeoutBidi")
    val delayInitial = name("delayInitial")
    val idleInject = name("idleInject")
    val backpressureTimeout = name("backpressureTimeout")

    val merge = name("merge")
    val mergePreferred = name("mergePreferred")
    val flattenMerge = name("flattenMerge")
    val recoverWith = name("recoverWith")
    val broadcast = name("broadcast")
    val balance = name("balance")
    val zip = name("zip")
    val zipN = name("zipN")
    val zipWithN = name("zipWithN")
    val unzip = name("unzip")
    val concat = name("concat")
    val repeat = name("repeat")
    val unfold = name("unfold")
    val unfoldAsync = name("unfoldAsync")
    val delay = name("delay")

    val terminationWatcher = name("terminationWatcher")

    val publisherSource = name("publisherSource")
    val iterableSource = name("iterableSource")
    val cycledSource = name("cycledSource")
    val futureSource = name("futureSource")
    val tickSource = name("tickSource")
    val singleSource = name("singleSource")
    val emptySource = name("emptySource")
    val maybeSource = name("MaybeSource")
    val failedSource = name("failedSource")
    val concatSource = name("concatSource")
    val concatMatSource = name("concatMatSource")
    val subscriberSource = name("subscriberSource")
    val actorPublisherSource = name("actorPublisherSource")
    val actorRefSource = name("actorRefSource")
    val queueSource = name("queueSource")
    val inputStreamSource = name("inputStreamSource") and IODispatcher
    val outputStreamSource = name("outputStreamSource") and IODispatcher
    val fileSource = name("fileSource") and IODispatcher
    val unfoldResourceSource = name("unfoldResourceSource") and IODispatcher
    val unfoldResourceSourceAsync = name("unfoldResourceSourceAsync") and IODispatcher
    val asJavaStream = name("asJavaStream") and IODispatcher
    val javaCollectorParallelUnordered = name("javaCollectorParallelUnordered")
    val javaCollector = name("javaCollector")

    val subscriberSink = name("subscriberSink")
    val cancelledSink = name("cancelledSink")
    val headSink = name("headSink") and inputBufferOne
    val headOptionSink = name("headOptionSink") and inputBufferOne
    val lastSink = name("lastSink")
    val lastOptionSink = name("lastOptionSink")
    val seqSink = name("seqSink")
    val publisherSink = name("publisherSink")
    val fanoutPublisherSink = name("fanoutPublisherSink")
    val ignoreSink = name("ignoreSink")
    val actorRefSink = name("actorRefSink")
    val actorRefWithAck = name("actorRefWithAckSink")
    val actorSubscriberSink = name("actorSubscriberSink")
    val queueSink = name("queueSink")
    val outputStreamSink = name("outputStreamSink") and IODispatcher
    val inputStreamSink = name("inputStreamSink") and IODispatcher
    val fileSink = name("fileSink") and IODispatcher
    val fromJavaStream = name("fromJavaStream")
  }

  import DefaultAttributes._

  /*
   * Stage that is backed by a GraphStage but can be symbolically introspected
   */
  case class SymbolicGraphStage[-In, +Out, Ext](symbolicStage: SymbolicStage[In, Out])
    extends PushPullGraphStage[In, Out, Ext](
      symbolicStage.create,
      symbolicStage.attributes) {
  }

  sealed trait SymbolicStage[-In, +Out] {
    def attributes: Attributes
    def create(effectiveAttributes: Attributes): Stage[In, Out]

    // FIXME: No supervision hooked in yet.

    protected def supervision(attributes: Attributes): Decider =
      attributes.get[SupervisionStrategy](SupervisionStrategy(Supervision.stoppingDecider)).decider

  }

  final case class Map[In, Out](f: In ⇒ Out, attributes: Attributes = map) extends SymbolicStage[In, Out] {
    override def create(attr: Attributes): Stage[In, Out] = fusing.Map(f, supervision(attr))
  }

  final case class Log[T](name: String, extract: T ⇒ Any, loggingAdapter: Option[LoggingAdapter], attributes: Attributes = log) extends SymbolicStage[T, T] {
    override def create(attr: Attributes): Stage[T, T] = fusing.Log(name, extract, loggingAdapter, supervision(attr))
  }

  final case class Grouped[T](n: Int, attributes: Attributes = grouped) extends SymbolicStage[T, immutable.Seq[T]] {
    require(n > 0, "n must be greater than 0")
    override def create(attr: Attributes): Stage[T, immutable.Seq[T]] = fusing.Grouped(n)
  }

  final case class Sliding[T](n: Int, step: Int, attributes: Attributes = sliding) extends SymbolicStage[T, immutable.Seq[T]] {
    require(n > 0, "n must be greater than 0")
    require(step > 0, "step must be greater than 0")

    override def create(attr: Attributes): Stage[T, immutable.Seq[T]] = fusing.Sliding(n, step)
  }

  final case class Fold[In, Out](zero: Out, f: (Out, In) ⇒ Out, attributes: Attributes = fold) extends SymbolicStage[In, Out] {
    override def create(attr: Attributes): Stage[In, Out] = fusing.Fold(zero, f, supervision(attr))
  }

  final case class Buffer[T](size: Int, overflowStrategy: OverflowStrategy, attributes: Attributes = buffer) extends SymbolicStage[T, T] {
    require(size > 0, s"Buffer size must be larger than zero but was [$size]")
    override def create(attr: Attributes): Stage[T, T] = fusing.Buffer(size, overflowStrategy)
  }

}
