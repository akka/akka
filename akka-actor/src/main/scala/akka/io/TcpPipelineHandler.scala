/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import scala.beans.BeanProperty
import scala.util.{ Failure, Success }
import akka.actor._
import akka.dispatch.{ RequiresMessageQueue, UnboundedMessageQueueSemantics }
import akka.util.ByteString
import akka.event.Logging
import akka.event.LoggingAdapter

object TcpPipelineHandler {

  /**
   * This class wraps up a pipeline with its external (i.e. “top”) command and
   * event types and providing unique wrappers for sending commands and
   * receiving events (nested and non-static classes which are specific to each
   * instance of [[Init]]). All events emitted by the pipeline will be sent to
   * the registered handler wrapped in an Event.
   */
  abstract class Init[Ctx <: PipelineContext, Cmd, Evt](
    val stages: PipelineStage[_ >: Ctx <: PipelineContext, Cmd, Tcp.Command, Evt, Tcp.Event]) {

    /**
     * This method must be implemented to return the [[PipelineContext]]
     * necessary for the operation of the given [[PipelineStage]].
     */
    def makeContext(actorContext: ActorContext): Ctx

    /**
     * Java API: construct a command to be sent to the [[TcpPipelineHandler]]
     * actor.
     */
    def command(cmd: Cmd): Command = Command(cmd)

    /**
     * Java API: extract a wrapped event received from the [[TcpPipelineHandler]]
     * actor.
     *
     * @throws MatchError if the given object is not an Event matching this
     *                    specific Init instance.
     */
    def event(evt: AnyRef): Evt = evt match {
      case Event(evt) ⇒ evt
    }

    /**
     * Wrapper class for commands to be sent to the [[TcpPipelineHandler]] actor.
     */
    case class Command(@BeanProperty cmd: Cmd) extends NoSerializationVerificationNeeded

    /**
     * Wrapper class for events emitted by the [[TcpPipelineHandler]] actor.
     */
    case class Event(@BeanProperty evt: Evt) extends NoSerializationVerificationNeeded
  }

  /**
   * This interface bundles logging and ActorContext for Java.
   */
  trait WithinActorContext extends HasLogging with HasActorContext

  def withLogger[Cmd, Evt](log: LoggingAdapter,
                           stages: PipelineStage[_ >: WithinActorContext <: PipelineContext, Cmd, Tcp.Command, Evt, Tcp.Event]): Init[WithinActorContext, Cmd, Evt] =
    new Init[WithinActorContext, Cmd, Evt](stages) {
      override def makeContext(ctx: ActorContext): WithinActorContext = new WithinActorContext {
        override def getLogger = log
        override def getContext = ctx
      }
    }

  /**
   * Wrapper class for management commands sent to the [[TcpPipelineHandler]] actor.
   */
  case class Management(@BeanProperty cmd: AnyRef)

  /**
   * This is a new Tcp.Command which the pipeline can emit to effect the
   * sending a message to another actor. Using this instead of doing the send
   * directly has the advantage that other pipeline stages can also see and
   * possibly transform the send.
   */
  case class Tell(receiver: ActorRef, msg: Any, sender: ActorRef) extends Tcp.Command

  /**
   * The pipeline may want to emit a [[Tcp.Event]] to the registered handler
   * actor, which is enabled by emitting this [[Tcp.Command]] wrapping an event
   * instead. The [[TcpPipelineHandler]] actor will upon reception of this command
   * forward the wrapped event to the handler.
   */
  case class TcpEvent(@BeanProperty evt: Tcp.Event) extends Tcp.Command

  /**
   * create [[akka.actor.Props]] for a pipeline handler
   */
  def props[Ctx <: PipelineContext, Cmd, Evt](init: TcpPipelineHandler.Init[Ctx, Cmd, Evt], connection: ActorRef, handler: ActorRef) =
    Props(classOf[TcpPipelineHandler[_, _, _]], init, connection, handler)

}

/**
 * This actor wraps a pipeline and forwards commands and events between that
 * one and a [[Tcp]] connection actor. In order to inject commands into the
 * pipeline send an [[TcpPipelineHandler.Init.Command]] message to this actor; events will be sent
 * to the designated handler wrapped in [[TcpPipelineHandler.Init.Event]] messages.
 *
 * When the designated handler terminates the TCP connection is aborted. When
 * the connection actor terminates this actor terminates as well; the designated
 * handler may want to watch this actor’s lifecycle.
 *
 * <b>IMPORTANT:</b>
 *
 * Proper function of this actor (and of other pipeline stages like [[TcpReadWriteAdapter]]
 * depends on the fact that stages handling TCP commands and events pass unknown
 * subtypes through unaltered. There are more commands and events than are declared
 * within the [[Tcp]] object and you can even define your own.
 */
class TcpPipelineHandler[Ctx <: PipelineContext, Cmd, Evt](
  init: TcpPipelineHandler.Init[Ctx, Cmd, Evt],
  connection: ActorRef,
  handler: ActorRef)
  extends Actor with RequiresMessageQueue[UnboundedMessageQueueSemantics] {

  import init._
  import TcpPipelineHandler._

  // sign death pact
  context watch connection
  // watch so we can Close
  context watch handler

  val ctx = init.makeContext(context)

  val pipes = PipelineFactory.buildWithSinkFunctions(ctx, init.stages)({
    case Success(cmd) ⇒
      cmd match {
        case Tell(receiver, msg, sender) ⇒ receiver.tell(msg, sender)
        case TcpEvent(ev)                ⇒ handler ! ev
        case _                           ⇒ connection ! cmd
      }
    case Failure(ex) ⇒ throw ex
  }, {
    case Success(evt) ⇒ handler ! Event(evt)
    case Failure(ex)  ⇒ throw ex
  })

  def receive = {
    case Command(cmd)             ⇒ pipes.injectCommand(cmd)
    case evt: Tcp.Event           ⇒ pipes.injectEvent(evt)
    case Management(cmd)          ⇒ pipes.managementCommand(cmd)
    case Terminated(`handler`)    ⇒ connection ! Tcp.Abort
    case Terminated(`connection`) ⇒ context.stop(self)
  }

}

/**
 * Adapts a ByteString oriented pipeline stage to a stage that communicates via Tcp Commands and Events. Every ByteString
 * passed down to this stage will be converted to Tcp.Write commands, while incoming Tcp.Receive events will be unwrapped
 * and their contents passed up as raw ByteStrings. This adapter should be used together with TcpPipelineHandler.
 *
 * While this adapter communicates to the stage above it via raw ByteStrings, it is possible to inject Tcp Command
 * by sending them to the management port, and the adapter will simply pass them down to the stage below. Incoming Tcp Events
 * that are not Receive events will be passed downwards wrapped in a [[TcpPipelineHandler.TcpEvent]]; the [[TcpPipelineHandler]] will
 * send these notifications to the registered event handler actor.
 */
class TcpReadWriteAdapter extends PipelineStage[PipelineContext, ByteString, Tcp.Command, ByteString, Tcp.Event] {
  import TcpPipelineHandler.TcpEvent

  override def apply(ctx: PipelineContext) = new PipePair[ByteString, Tcp.Command, ByteString, Tcp.Event] {

    override val commandPipeline = {
      data: ByteString ⇒ ctx.singleCommand(Tcp.Write(data))
    }

    override val eventPipeline = (evt: Tcp.Event) ⇒ evt match {
      case Tcp.Received(data) ⇒ ctx.singleEvent(data)
      case ev: Tcp.Event      ⇒ ctx.singleCommand(TcpEvent(ev))
    }

    override val managementPort: Mgmt = {
      case cmd: Tcp.Command ⇒ ctx.singleCommand(cmd)
    }
  }
}
