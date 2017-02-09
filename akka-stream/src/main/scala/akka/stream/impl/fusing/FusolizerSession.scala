package akka.stream.impl.fusing

import java.util.ArrayList

import akka.stream._
import akka.stream.impl.NewLayout._
import akka.stream.impl.StreamLayout.Module
import akka.stream.impl._
import akka.stream.impl.io.TlsModule
import akka.stream.stage.{GraphStageLogic, InHandler, OutHandler}
import org.reactivestreams.{Publisher, Subscriber}

object FusolizerSession {
  val DefaultArraySize = 16
}

// TODO: To figure out/finish
// - How to assign stage IDs (and how to track the list of Connections in general)
// - How to get the BoundarySubscriber and BoundaryPublisher without knowing about any actors related stuff
// - Remove GraphAssembly from the shell (and in general)
// - teach the builders about the AsyncBoundary marker
// - make it compile ;)
// - make it work ;)

abstract class FusolizerSession {
  import FusolizerSession._

  private[this] val publishers  = new ArrayList[Publisher[Any]](DefaultArraySize)
  private[this] val subscribers = new ArrayList[Subscriber[Any]](DefaultArraySize)

  // TODO: Avoid boxing
  private[this] val inOwnerIds  = new ArrayList[Int](DefaultArraySize)
  private[this] val inOwners    = new ArrayList[GraphStageLogic](DefaultArraySize)
  private[this] val outOwnerIds = new ArrayList[Int](DefaultArraySize)
  private[this] val outOwners   = new ArrayList[GraphStageLogic](DefaultArraySize)
  private[this] val inHandlers  = new ArrayList[InHandler](DefaultArraySize)
  private[this] val outHandlers = new ArrayList[OutHandler](DefaultArraySize)
  // Tracks the fused island we are currently at, used to check for cross-island links
  private[this] val island      = new ArrayList[Traversal](DefaultArraySize)

  private[this] var inSlot = 0
  private[this] var stageId = 0
  private[this] var currentIsland: Traversal = _
  private[this] var currentOutToSlot: Array[Int] = _

  abstract def materializeAtomic(mod: Module): Unit

  abstract def materializeFused(shell: GraphInterpreterShell): Unit

  final def fusolize(traversal: Traversal): Unit = {
    currentIsland = traversal

    var current: Traversal = traversal
    val traversalStack = new ArrayList[Traversal](16)
    traversalStack.add(current)

    // Due to how Concat works, we need a stack. This probably can be optimized for the most common cases.
    while (!traversalStack.isEmpty) {
      current = traversalStack.remove(traversalStack.size() - 1)

      while (current ne EmptyTraversal) {
        current match {
          case MaterializeAtomic(mod, outToSlot) ⇒
            currentOutToSlot = outToSlot
            materializeAtomic(mod)
            inSlot += mod.shape.inlets.size
            current = current.next
            // FIXME: somehow check if this was a fusable module or not, otherwise must not increment this
            stageId += 1
          case Concat(first, next) ⇒
            traversalStack.add(next)
            current = first
          case AsyncBoundary(next) ⇒
            val savedStageID = stageId
            fusolize(next)
            // Restore state
            currentIsland = traversal
            stageId = savedStageID
          case _ ⇒
            current = current.next
        }
      }
    }

    // TODO: Finish connections and call materilizeFused
  }


  protected final def assign(in: InPort, subscriber: Subscriber[Any]): Unit = {
    val slot = inSlot + in.id
    val publisher = publishers.get(slot)
    if (publisher ne null) publisher.subscribe(subscriber)
    else {
      subscribers.set(slot, subscriber)
      island.set(slot, currentIsland)
    }
  }

  protected final def assign(out: OutPort, publisher: Publisher[Any]): Unit = {
    val slot = inSlot + currentOutToSlot(out.id)
    val subscriber = subscribers.get(slot)
    if (subscriber ne null) publisher.subscribe(subscriber)
    else {
      publishers.set(slot, publisher)
      island.set(slot, currentIsland)
    }
  }

  protected final def assign(in: InPort, graphStageLogic: GraphStageLogic): Unit = {
    val slot = inSlot + in.id
    val slotIsland = island.get(slot)
    inOwners.set(slot, graphStageLogic)
    inOwnerIds.set(slot, stageId)
    inHandlers.set(slot, graphStageLogic.handlers(in.id).asInstanceOf[InHandler])

    // Cross island wiring
    if ((slotIsland ne currentIsland) && (slotIsland ne null)) {
      val subscriber = new ActorGraphInterpreter.BoundarySubscriber(impl, shell, in.id)
      assign(in, subscriber)
    } else island.set(slot, currentIsland)
  }

  protected final def assign(out: OutPort, graphStageLogic: GraphStageLogic): Unit = {
    val slot = inSlot + currentOutToSlot(out.id)
    val slotIsland = island.get(slot)
    outOwners.set(slot, graphStageLogic)
    outOwnerIds.set(slot, stageId)
    outHandlers.set(slot, graphStageLogic.handlers(graphStageLogic.inCount + out.id).asInstanceOf[OutHandler])

    // Cross island wiring
    if ((slotIsland ne currentIsland) && (slotIsland ne null)) {
      val publisher = new ActorGraphInterpreter.BoundaryPublisher(impl, shell, out.id)
      impl ! ActorGraphInterpreter.ExposedPublisher(shell, i, publisher)
      assign(out, publisher)
    } else island.set(slot, currentIsland)

  }

}
