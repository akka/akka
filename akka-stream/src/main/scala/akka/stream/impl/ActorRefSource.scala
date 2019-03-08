/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import akka.actor.{ ActorRef, PoisonPill }
import akka.annotation.InternalApi
import akka.stream.OverflowStrategies._
import akka.stream._
import akka.stream.stage._

import scala.annotation.tailrec

object ActorRefSource {
  case object EagerComplete

  private sealed trait ActorRefStage { def ref: ActorRef }
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class ActorRefSource[T](
    maxBuffer: Int,
    overflowStrategy: OverflowStrategy,
    completionMatcher: PartialFunction[Any, Unit],
    failureMatcher: PartialFunction[Any, Throwable])
    extends GraphStageWithEagerMaterializedValue[SourceShape[T], ActorRef] {
  import ActorRefSource._

  val out: Outlet[T] = Outlet[T]("actorRefSource.out")

  override val shape: SourceShape[T] = SourceShape.of(out)

  override def createLogicAndEagerMaterializedValue(
      inheritedAttributes: Attributes,
      eagerMaterializer: Materializer): (GraphStageLogic, ActorRef) = {
    val stage: GraphStageLogic with StageLogging with ActorRefStage = new GraphStageLogic(shape) with StageLogging
    with ActorRefStage {
      override protected def logSource: Class[_] = classOf[ActorRefSource[_]]

      private val buffer: Buffer[T] =
        if (maxBuffer != 0)
          Buffer(maxBuffer, eagerMaterializer)
        else {
          null // todo: for backwards compatibility only
        }
      private var isCompleting: Boolean = false

      override def preStart(): Unit = {
        super.preStart()
        if (maxBuffer == 0)
          log.warning("for backwards compatibility: maxBuffer of 0 will not be supported in the future") // warning for backwards compatibility
      }

      override protected def stageActorName: String =
        inheritedAttributes.get[Attributes.Name].map(_.n).getOrElse(super.stageActorName)

      val ref: ActorRef = getEagerStageActor(eagerMaterializer, poisonPillFallback = true) {
        case (_, EagerComplete) ⇒
          completeStage()
        case (_, PoisonPill) ⇒
          log.warning("for backwards compatibility: PoisonPill will note be supported in the future")
          isCompleting = true
          pump()
        case (_, m) if failureMatcher.isDefinedAt(m) ⇒
          failStage(failureMatcher(m))
        case (_, m) if completionMatcher.isDefinedAt(m) ⇒
          isCompleting = true
          pump()
        case (_, m: T) ⇒ // todo: requires classTag (breaks api)
          if (isCompleting) {
            log.warning(
              "Dropping element because Status.Success received already, only draining already buffered elements: [{}] (pending: [{}])",
              m,
              buffer.used)
          } else if (buffer == null) { // todo: remove - for backwards compatibility only!
            if (isAvailable(out)) push(out, m)
            else log.debug("Dropping element because there is no downstream demand: [{}]", m)
          } else if (!buffer.isFull) {
            buffer.enqueue(m)
            pump()
          } else
            overflowStrategy match {
              case s: DropHead ⇒
                log.log(
                  s.logLevel,
                  "Dropping the head element because buffer is full and overflowStrategy is: [DropHead]")
                buffer.dropHead()
                buffer.enqueue(m)
                pump()
              case s: DropTail ⇒
                log.log(
                  s.logLevel,
                  "Dropping the tail element because buffer is full and overflowStrategy is: [DropTail]")
                buffer.dropTail()
                buffer.enqueue(m)
                pump()
              case s: DropBuffer ⇒
                log.log(
                  s.logLevel,
                  "Dropping all the buffered elements because buffer is full and overflowStrategy is: [DropBuffer]")
                buffer.clear()
                buffer.enqueue(m)
                pump()
              case s: DropNew ⇒
                log.log(
                  s.logLevel,
                  "Dropping the new element because buffer is full and overflowStrategy is: [DropNew]")
              case s: Fail ⇒
                log.log(s.logLevel, "Failing because buffer is full and overflowStrategy is: [Fail]")
                val bufferOverflowException =
                  BufferOverflowException(s"Buffer overflow (max capacity was: $maxBuffer)!")
                failStage(bufferOverflowException)
              case _: Backpressure ⇒
              // there is a precondition check in Source.actorRefSource factory method to not allow backpressure as strategy
            }

        case (_, m) ⇒
          log.warning("Dropping unexpected element: [{}]", m)
      }.ref

      @tailrec
      private def pump(): Unit = {
        if (isAvailable(out) && buffer.nonEmpty) {
          val msg = buffer.dequeue()
          push(out, msg)
          pump()
        } else if (isCompleting && buffer.isEmpty) {
          completeStage()
        }
      }

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          pump()
        }
      })
    }

    (stage, stage.ref)
  }
}
