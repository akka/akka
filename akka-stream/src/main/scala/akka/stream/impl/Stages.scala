/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.event.{ LoggingAdapter, Logging }
import akka.stream.impl.SplitDecision.SplitDecision
import akka.stream.{ OverflowStrategy, TimerTransformer }
import akka.stream.OperationAttributes
import akka.stream.OperationAttributes._
import akka.stream.stage.Stage
import org.reactivestreams.Processor
import StreamLayout._

import scala.collection.immutable
import scala.concurrent.Future

/**
 * INTERNAL API
 */
private[stream] object Stages {

  object DefaultAttributes {
    val timerTransform = name("timerTransform")
    val stageFactory = name("stageFactory")
    val fused = name("fused")
    val map = name("map")
    val filter = name("filter")
    val collect = name("collect")
    val recover = name("recover")
    val mapAsync = name("mapAsync")
    val mapAsyncUnordered = name("mapAsyncUnordered")
    val grouped = name("grouped")
    val take = name("take")
    val drop = name("drop")
    val takeWhile = name("takeWhile")
    val dropWhile = name("dropWhile")
    val scan = name("scan")
    val buffer = name("buffer")
    val conflate = name("conflate")
    val expand = name("expand")
    val mapConcat = name("mapConcat")
    val groupBy = name("groupBy")
    val prefixAndTail = name("prefixAndTail")
    val split = name("split")
    val concatAll = name("concatAll")
    val processor = name("processor")
    val processorWithKey = name("processorWithKey")
    val identityOp = name("identityOp")

    val merge = name("merge")
    val mergePreferred = name("mergePreferred")
    val broadcast = name("broadcast")
    val balance = name("balance")
    val zip = name("zip")
    val unzip = name("unzip")
    val concat = name("concat")
    val flexiMerge = name("flexiMerge")
    val flexiRoute = name("flexiRoute")
    val identityJunction = name("identityJunction")
    val repeat = name("repeat")

    val publisherSource = name("publisherSource")
    val iterableSource = name("iterableSource")
    val futureSource = name("futureSource")
    val tickSource = name("tickSource")
    val singleSource = name("singleSource")
    val emptySource = name("emptySource")
    val lazyEmptySource = name("lazyEmptySource")
    val failedSource = name("failedSource")
    val concatSource = name("concatSource")
    val concatMatSource = name("concatMatSource")
    val subscriberSource = name("subscriberSource")
    val actorPublisherSource = name("actorPublisherSource")
    val actorRefSource = name("actorRefSource")
    val synchronousFileSource = name("synchronousFileSource")
    val inputStreamSource = name("inputStreamSource")

    val subscriberSink = name("subscriberSink")
    val cancelledSink = name("cancelledSink")
    val headSink = name("headSink")
    val publisherSink = name("publisherSink")
    val fanoutPublisherSink = name("fanoutPublisherSink")
    val ignoreSink = name("ignoreSink")
    val actorRefSink = name("actorRefSink")
    val actorSubscriberSink = name("actorSubscriberSink")
    val synchronousFileSink = name("synchronousFileSink")
    val outputStreamSink = name("outputStreamSink")
  }

  import DefaultAttributes._

  sealed trait StageModule extends FlowModule[Any, Any, Any] {

    def attributes: OperationAttributes
    def withAttributes(attributes: OperationAttributes): StageModule

    protected def newInstance: StageModule
    override def carbonCopy: Module = newInstance
  }

  final case class TimerTransform(mkStage: () ⇒ TimerTransformer[Any, Any], attributes: OperationAttributes = timerTransform) extends StageModule {
    def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
    override protected def newInstance: StageModule = this.copy()
  }

  final case class StageFactory(mkStage: () ⇒ Stage[_, _], attributes: OperationAttributes = stageFactory) extends StageModule {
    def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
    override protected def newInstance: StageModule = this.copy()
  }

  final case class MaterializingStageFactory(
    mkStageAndMaterialized: () ⇒ (Stage[_, _], Any),
    attributes: OperationAttributes = stageFactory) extends StageModule {
    def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
    override protected def newInstance: StageModule = this.copy()
  }

  final case class Identity(attributes: OperationAttributes = OperationAttributes.name("identity")) extends StageModule {
    def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
    override protected def newInstance: StageModule = this.copy()
  }

  object Fused {
    def apply(ops: immutable.Seq[Stage[_, _]]): Fused =
      Fused(ops, name(ops.iterator.map(x ⇒ Logging.simpleName(x).toLowerCase).mkString("+")))
  }

  final case class Fused(ops: immutable.Seq[Stage[_, _]], attributes: OperationAttributes = fused) extends StageModule {
    def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
    override protected def newInstance: StageModule = this.copy()
  }

  final case class Map(f: Any ⇒ Any, attributes: OperationAttributes = map) extends StageModule {
    def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
    override protected def newInstance: StageModule = this.copy()
  }

  final case class Log(name: String, extract: Any ⇒ Any, loggingAdapter: Option[LoggingAdapter], attributes: OperationAttributes = map) extends StageModule {
    def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
    override protected def newInstance: StageModule = this.copy()
  }

  final case class Filter(p: Any ⇒ Boolean, attributes: OperationAttributes = filter) extends StageModule {
    def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
    override protected def newInstance: StageModule = this.copy()
  }

  final case class Recover(pf: PartialFunction[Any, Any], attributes: OperationAttributes = recover) extends StageModule {
    def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
    override protected def newInstance: StageModule = this.copy()
  }

  final case class Collect(pf: PartialFunction[Any, Any], attributes: OperationAttributes = collect) extends StageModule {
    def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
    override protected def newInstance: StageModule = this.copy()
  }

  final case class MapAsync(parallelism: Int, f: Any ⇒ Future[Any], attributes: OperationAttributes = mapAsync) extends StageModule {
    def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
    override protected def newInstance: StageModule = this.copy()
  }

  final case class MapAsyncUnordered(parallelism: Int, f: Any ⇒ Future[Any], attributes: OperationAttributes = mapAsyncUnordered) extends StageModule {
    def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
    override protected def newInstance: StageModule = this.copy()
  }

  final case class Grouped(n: Int, attributes: OperationAttributes = grouped) extends StageModule {
    require(n > 0, "n must be greater than 0")

    def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
    override protected def newInstance: StageModule = this.copy()
  }

  final case class Take(n: Long, attributes: OperationAttributes = take) extends StageModule {
    def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
    override protected def newInstance: StageModule = this.copy()
  }

  final case class Drop(n: Long, attributes: OperationAttributes = drop) extends StageModule {
    def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
    override protected def newInstance: StageModule = this.copy()
  }

  final case class TakeWhile(p: Any ⇒ Boolean, attributes: OperationAttributes = takeWhile) extends StageModule {
    def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
    override protected def newInstance: StageModule = this.copy()
  }

  final case class DropWhile(p: Any ⇒ Boolean, attributes: OperationAttributes = dropWhile) extends StageModule {
    def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
    override protected def newInstance: StageModule = this.copy()
  }

  final case class Scan(zero: Any, f: (Any, Any) ⇒ Any, attributes: OperationAttributes = scan) extends StageModule {
    def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
    override protected def newInstance: StageModule = this.copy()
  }

  final case class Buffer(size: Int, overflowStrategy: OverflowStrategy, attributes: OperationAttributes = buffer) extends StageModule {
    require(size > 0, s"Buffer size must be larger than zero but was [$size]")

    def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
    override protected def newInstance: StageModule = this.copy()
  }
  final case class Conflate(seed: Any ⇒ Any, aggregate: (Any, Any) ⇒ Any, attributes: OperationAttributes = conflate) extends StageModule {
    def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
    override protected def newInstance: StageModule = this.copy()
  }
  final case class Expand(seed: Any ⇒ Any, extrapolate: Any ⇒ (Any, Any), attributes: OperationAttributes = expand) extends StageModule {
    def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
    override protected def newInstance: StageModule = this.copy()
  }
  final case class MapConcat(f: Any ⇒ immutable.Iterable[Any], attributes: OperationAttributes = mapConcat) extends StageModule {
    def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
    override protected def newInstance: StageModule = this.copy()
  }

  final case class GroupBy(f: Any ⇒ Any, attributes: OperationAttributes = groupBy) extends StageModule {
    def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
    override protected def newInstance: StageModule = this.copy()
  }

  final case class PrefixAndTail(n: Int, attributes: OperationAttributes = prefixAndTail) extends StageModule {
    def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
    override protected def newInstance: StageModule = this.copy()
  }

  final case class Split(p: Any ⇒ SplitDecision, attributes: OperationAttributes = split) extends StageModule {
    def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
    override protected def newInstance: StageModule = this.copy()
  }

  final case class ConcatAll(attributes: OperationAttributes = concatAll) extends StageModule {
    def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
    override protected def newInstance: StageModule = this.copy()
  }

  final case class DirectProcessor(p: () ⇒ (Processor[Any, Any], Any), attributes: OperationAttributes = processor) extends StageModule {
    def withAttributes(attributes: OperationAttributes) = copy(attributes = attributes)
    override protected def newInstance: StageModule = this.copy()
  }

}
