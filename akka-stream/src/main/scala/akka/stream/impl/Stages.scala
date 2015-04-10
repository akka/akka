/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.event.Logging
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
    val mapAsync = name("mapAsync")
    val mapAsyncUnordered = name("mapAsyncUnordered")
    val grouped = name("grouped")
    val take = name("take")
    val drop = name("drop")
    val scan = name("scan")
    val buffer = name("buffer")
    val conflate = name("conflate")
    val expand = name("expand")
    val mapConcat = name("mapConcat")
    val groupBy = name("groupBy")
    val prefixAndTail = name("prefixAndTail")
    val splitWhen = name("splitWhen")
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

  final case class Filter(p: Any ⇒ Boolean, attributes: OperationAttributes = filter) extends StageModule {
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
  final case class MapConcat(f: Any ⇒ immutable.Seq[Any], attributes: OperationAttributes = mapConcat) extends StageModule {
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

  final case class SplitWhen(p: Any ⇒ Boolean, attributes: OperationAttributes = splitWhen) extends StageModule {
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
