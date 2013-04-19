/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import akka.actor.Actor
import akka.actor.ActorContext
import scala.beans.BeanProperty
import akka.actor.ActorRef
import scala.util.Success
import scala.util.Failure
import akka.actor.Terminated
import akka.actor.Props

object TcpPipelineHandler {

  /**
   * This class wraps up a pipeline with its external (i.e. “top”) command and
   * event types and providing unique wrappers for sending commands and
   * receiving events (nested and non-static classes which are specific to each
   * instance of [[Init]]). All events emitted by the pipeline will be sent to
   * the registered handler wrapped in an Event.
   */
  abstract class Init[Ctx <: PipelineContext, Cmd, Evt](val stages: PipelineStage[Ctx, Cmd, Tcp.Command, Evt, Tcp.Event]) {
    def makeContext(actorContext: ActorContext): Ctx

    def command(cmd: Cmd): Command = Command(cmd)
    def event(evt: AnyRef): Evt = evt match {
      case Event(evt) ⇒ evt
    }

    final case class Command(@BeanProperty cmd: Cmd)
    final case class Event(@BeanProperty evt: Evt)
  }

  /**
   * Wrapper around acknowledgements: if a Tcp.Write is generated which
   * request an ACK then it is wrapped such that the ACK can flow back up the
   * pipeline later, allowing you to use arbitrary ACK messages (not just
   * subtypes of Tcp.Event).
   */
  case class Ack(ack: Any) extends Tcp.Event

  /**
   * This is a new Tcp.Command which the pipeline can emit to effect the
   * sending a message to another actor. Using this instead of doing the send
   * directly has the advantage that other pipeline stages can also see and
   * possibly transform the send.
   */
  case class Tell(receiver: ActorRef, msg: Any, sender: ActorRef) extends Tcp.Command

  /**
   * Scala API: create [[Props]] for a pipeline handler
   */
  def apply[Ctx <: PipelineContext, Cmd, Evt](init: TcpPipelineHandler.Init[Ctx, Cmd, Evt], connection: ActorRef, handler: ActorRef) =
    Props(classOf[TcpPipelineHandler[_, _, _]], init, connection, handler)

  /**
   * Java API: create [[Props]] for a pipeline handler
   */
  def create[Ctx <: PipelineContext, Cmd, Evt](init: TcpPipelineHandler.Init[Ctx, Cmd, Evt], connection: ActorRef, handler: ActorRef) =
    Props(classOf[TcpPipelineHandler[_, _, _]], init, connection, handler)

}

/**
 * This actor wraps a pipeline and forwards commands and events between that
 * one and a [[Tcp]] connection actor. In order to inject commands into the
 * pipeline send an [[Init.Command]] message to this actor; events will be sent
 * to the designated handler wrapped in [[Init.Event]] messages.
 *
 * When the designated handler terminates the TCP connection is aborted. When
 * the connection actor terminates this actor terminates as well; the designated
 * handler may want to watch this actor’s lifecycle.
 *
 * <b>FIXME WARNING:</b> (Ticket 3253)
 *
 * This actor does currently not handle back-pressure from the TCP socket; it
 * is meant only as a demonstration and will be fleshed out in full before the
 * 2.2 release.
 */
class TcpPipelineHandler[Ctx <: PipelineContext, Cmd, Evt](
  init: TcpPipelineHandler.Init[Ctx, Cmd, Evt],
  connection: ActorRef,
  handler: ActorRef)
  extends Actor {

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
        case Tcp.Write(data, Tcp.NoAck)  ⇒ connection ! cmd
        case Tcp.Write(data, ack)        ⇒ connection ! Tcp.Write(data, Ack(ack))
        case Tell(receiver, msg, sender) ⇒ receiver.tell(msg, sender)
        case _                           ⇒ connection ! cmd
      }
    case Failure(ex) ⇒ throw ex
  }, {
    case Success(evt) ⇒ handler ! Event(evt)
    case Failure(ex)  ⇒ throw ex
  })

  def receive = {
    case Command(cmd)          ⇒ pipes.injectCommand(cmd)
    case evt: Tcp.Event        ⇒ pipes.injectEvent(evt)
    case Terminated(`handler`) ⇒ connection ! Tcp.Abort
  }

}
