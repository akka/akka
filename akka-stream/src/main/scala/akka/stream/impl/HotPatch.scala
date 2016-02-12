package akka.stream.impl

import akka.event.Logging
import akka.stream.Attributes.Attribute
import akka.stream._
import akka.stream.impl.HotPatch.HotPatchAttribute
import akka.stream.stage.{ GraphStageLogic, GraphStage }

/**
 * INTERNAL API
 */
private[akka] object HotPatch {

  case class HotPatchAttribute[I, O](placeholder: HotPatch[I, O], replacement: GraphStage[FlowShape[I, O]])
    extends Attribute

  def patch[I, O](placeholder: HotPatch[I, O], replacement: GraphStage[FlowShape[I, O]]): Attributes = {
    Attributes(HotPatchAttribute(placeholder, replacement))
  }

}

/**
 * INTERNAL API
 *
 * The purpose of this stage is to allow prefusing a large graph by placing this placeholder at a position where
 * a dynamically injected Flow is required. The actual Flow can be injected by an Attribute just before materialization.
 */
private[akka] class HotPatch[I, O] extends GraphStage[FlowShape[I, O]] {
  val in = Inlet[I](Logging.simpleName(this) + ".in")
  val out = Outlet[O](Logging.simpleName(this) + ".out")
  override val shape = FlowShape(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {
    val thisStage = this.asInstanceOf[HotPatch[Any, Any]]
    val patchStage = inheritedAttributes.attributeList.collectFirst {
      case HotPatchAttribute(`thisStage`, replacement) ⇒ replacement
    }

    patchStage match {
      case Some(stage) ⇒

        // Shape needs to be initialized
        var idx = 0
        val inletItr = stage.shape.inlets.iterator
        while (inletItr.hasNext) {
          val inlet = inletItr.next()
          require(inlet.id == -1 || inlet.id == idx, s"Inlet $inlet was shared among multiple stages. This is illegal.")
          inlet.id = idx
          idx += 1
        }

        idx = 0
        val outletItr = stage.shape.outlets.iterator
        while (outletItr.hasNext) {
          val outlet = outletItr.next()
          require(outlet.id == -1 || outlet.id == idx, s"Outlet $outlet was shared among multiple stages. This is illegal.")
          outlet.id = idx
          idx += 1
        }

        stage.createLogic(inheritedAttributes)

      case None ⇒
        throw new UnsupportedOperationException("Cannot materialize PrefusableFlowPlaceholder without a replacement Attribute present.")
    }
  }
}
