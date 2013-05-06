/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.lang.{ Iterable ⇒ JIterable }
import scala.annotation.tailrec
import scala.util.{ Try, Success, Failure }
import java.nio.ByteOrder
import akka.util.ByteString
import scala.collection.mutable
import akka.actor.ActorContext
import scala.concurrent.duration.FiniteDuration
import scala.collection.mutable.WrappedArray
import scala.concurrent.duration.Deadline
import scala.beans.BeanProperty
import akka.event.LoggingAdapter

/**
 * Scala API: A pair of pipes, one for commands and one for events, plus a
 * management port. Commands travel from top to bottom, events from bottom to
 * top. All messages which need to be handled “in-order” (e.g. top-down or
 * bottom-up) need to be either events or commands; management messages are
 * processed in no particular order.
 *
 * Java base classes are provided in the form of [[AbstractPipePair]]
 * and [[AbstractSymmetricPipePair]] since the Scala function types can be
 * awkward to handle in Java.
 *
 * @see [[PipelineStage]]
 * @see [[AbstractPipePair]]
 * @see [[AbstractSymmetricPipePair]]
 * @see [[PipePairFactory]]
 */
trait PipePair[CmdAbove, CmdBelow, EvtAbove, EvtBelow] {

  type Result = Either[EvtAbove, CmdBelow]
  type Mgmt = PartialFunction[AnyRef, Iterable[Result]]

  /**
   * The command pipeline transforms injected commands from the upper stage
   * into commands for the stage below, but it can also emit events for the
   * upper stage. Any number of each can be generated.
   */
  def commandPipeline: CmdAbove ⇒ Iterable[Result]

  /**
   * The event pipeline transforms injected event from the lower stage
   * into event for the stage above, but it can also emit commands for the
   * stage below. Any number of each can be generated.
   */
  def eventPipeline: EvtBelow ⇒ Iterable[Result]

  /**
   * The management port allows sending broadcast messages to all stages
   * within this pipeline. This can be used to communicate with stages in the
   * middle without having to thread those messages through the surrounding
   * stages. Each stage can generate events and commands in response to a
   * command, and the aggregation of all those is returned.
   *
   * The default implementation ignores all management commands.
   */
  def managementPort: Mgmt = PartialFunction.empty
}

/**
 * A convenience type for expressing a [[PipePair]] which has the same types
 * for commands and events.
 */
trait SymmetricPipePair[Above, Below] extends PipePair[Above, Below, Above, Below]

/**
 * Java API: A pair of pipes, one for commands and one for events. Commands travel from
 * top to bottom, events from bottom to top.
 *
 * @see [[PipelineStage]]
 * @see [[AbstractSymmetricPipePair]]
 * @see [[PipePairFactory]]
 */
abstract class AbstractPipePair[CmdAbove, CmdBelow, EvtAbove, EvtBelow] {

  /**
   * Commands reaching this pipe pair are transformed into a sequence of
   * commands for the next or events for the previous stage.
   *
   * Throwing exceptions within this method will abort processing of the whole
   * pipeline which this pipe pair is part of.
   *
   * @param cmd the incoming command
   * @return an Iterable of elements which are either events or commands
   *
   * @see [[#makeCommand]]
   * @see [[#makeEvent]]
   */
  def onCommand(cmd: CmdAbove): JIterable[Either[EvtAbove, CmdBelow]]

  /**
   * Events reaching this pipe pair are transformed into a sequence of
   * commands for the next or events for the previous stage.
   *
   * Throwing exceptions within this method will abort processing of the whole
   * pipeline which this pipe pair is part of.
   *
   * @param cmd the incoming command
   * @return an Iterable of elements which are either events or commands
   *
   * @see [[#makeCommand]]
   * @see [[#makeEvent]]
   */
  def onEvent(event: EvtBelow): JIterable[Either[EvtAbove, CmdBelow]]

  /**
   * Management commands are sent to all stages in a broadcast fashion,
   * conceptually in parallel (but not actually executing a stage
   * reentrantly in case of events or commands being generated in response
   * to a management command).
   */
  def onManagementCommand(cmd: AnyRef): JIterable[Either[EvtAbove, CmdBelow]] =
    java.util.Collections.emptyList()

  /**
   * Helper method for wrapping a command which shall be emitted.
   */
  def makeCommand(cmd: CmdBelow): Either[EvtAbove, CmdBelow] = Right(cmd)

  /**
   * Helper method for wrapping an event which shall be emitted.
   */
  def makeEvent(event: EvtAbove): Either[EvtAbove, CmdBelow] = Left(event)

  /**
   * INTERNAL API: do not touch!
   */
  private[io] val _internal$cmd = {
    val l = new java.util.ArrayList[AnyRef](1)
    l add null
    l
  }
  /**
   * INTERNAL API: do not touch!
   */
  private[io] val _internal$evt = {
    val l = new java.util.ArrayList[AnyRef](1)
    l add null
    l
  }

  /**
   * Wrap a single command for efficient return to the pipeline’s machinery.
   * This method avoids allocating a [[Right]] and an [[java.lang.Iterable]] by reusing
   * one such instance within the AbstractPipePair, hence it can be used ONLY ONCE by
   * each pipeline stage. Prototypic and safe usage looks like this:
   *
   * {{{
   * final MyResult result = ... ;
   * return singleCommand(result);
   * }}}
   *
   * @see PipelineContext#singleCommand
   */
  def singleCommand(cmd: CmdBelow): JIterable[Either[EvtAbove, CmdBelow]] = {
    _internal$cmd.set(0, cmd.asInstanceOf[AnyRef])
    _internal$cmd.asInstanceOf[JIterable[Either[EvtAbove, CmdBelow]]]
  }

  /**
   * Wrap a single event for efficient return to the pipeline’s machinery.
   * This method avoids allocating a [[Left]] and an [[Iterable]] by reusing
   * one such instance within the AbstractPipePair, hence it can be used ONLY ONCE by
   * each pipeline stage. Prototypic and safe usage looks like this:
   *
   * {{{
   * final MyResult result = ... ;
   * return singleEvent(result);
   * }}}
   *
   * @see PipelineContext#singleEvent
   */
  def singleEvent(evt: EvtAbove): JIterable[Either[EvtAbove, CmdBelow]] = {
    _internal$evt.set(0, evt.asInstanceOf[AnyRef])
    _internal$evt.asInstanceOf[JIterable[Either[EvtAbove, CmdBelow]]]
  }

  /**
   * INTERNAL API: Dealias a possibly optimized return value such that it can
   * be safely used; this is never needed when only using public API.
   */
  def dealias[Cmd, Evt](msg: JIterable[Either[Evt, Cmd]]): JIterable[Either[Evt, Cmd]] = {
    import java.util.Collections.singletonList
    if (msg eq _internal$cmd) singletonList(Right(_internal$cmd.get(0).asInstanceOf[Cmd]))
    else if (msg eq _internal$evt) singletonList(Left(_internal$evt.get(0).asInstanceOf[Evt]))
    else msg
  }
}

/**
 * A convenience type for expressing a [[AbstractPipePair]] which has the same types
 * for commands and events.
 */
abstract class AbstractSymmetricPipePair[Above, Below] extends AbstractPipePair[Above, Below, Above, Below]

/**
 * This class contains static factory methods which produce [[PipePair]]
 * instances; those are needed within the implementation of [[PipelineStage#apply]].
 */
object PipePairFactory {

  /**
   * Scala API: construct a [[PipePair]] from the two given functions; useful for not capturing `$outer` references.
   */
  def apply[CmdAbove, CmdBelow, EvtAbove, EvtBelow] //
  (commandPL: CmdAbove ⇒ Iterable[Either[EvtAbove, CmdBelow]],
   eventPL: EvtBelow ⇒ Iterable[Either[EvtAbove, CmdBelow]],
   management: PartialFunction[AnyRef, Iterable[Either[EvtAbove, CmdBelow]]] = PartialFunction.empty) =
    new PipePair[CmdAbove, CmdBelow, EvtAbove, EvtBelow] {
      override def commandPipeline = commandPL
      override def eventPipeline = eventPL
      override def managementPort = management
    }

  private abstract class Converter[CmdAbove <: AnyRef, CmdBelow <: AnyRef, EvtAbove <: AnyRef, EvtBelow <: AnyRef] //
  (val ap: AbstractPipePair[CmdAbove, CmdBelow, EvtAbove, EvtBelow], ctx: PipelineContext) {
    import scala.collection.JavaConverters._
    protected def normalize(output: JIterable[Either[EvtAbove, CmdBelow]]): Iterable[Either[EvtAbove, CmdBelow]] =
      if (output == java.util.Collections.EMPTY_LIST) Nil
      else if (output eq ap._internal$cmd) ctx.singleCommand(ap._internal$cmd.get(0).asInstanceOf[CmdBelow])
      else if (output eq ap._internal$evt) ctx.singleEvent(ap._internal$evt.get(0).asInstanceOf[EvtAbove])
      else output.asScala
  }

  /**
   * Java API: construct a [[PipePair]] from the given [[AbstractPipePair]].
   */
  def create[CmdAbove <: AnyRef, CmdBelow <: AnyRef, EvtAbove <: AnyRef, EvtBelow <: AnyRef] //
  (ctx: PipelineContext, ap: AbstractPipePair[CmdAbove, CmdBelow, EvtAbove, EvtBelow]) //
  : PipePair[CmdAbove, CmdBelow, EvtAbove, EvtBelow] =
    new Converter(ap, ctx) with PipePair[CmdAbove, CmdBelow, EvtAbove, EvtBelow] {
      override val commandPipeline = { cmd: CmdAbove ⇒ normalize(ap.onCommand(cmd)) }
      override val eventPipeline = { evt: EvtBelow ⇒ normalize(ap.onEvent(evt)) }
      override val managementPort: Mgmt = { case x ⇒ normalize(ap.onManagementCommand(x)) }
    }

  /**
   * Java API: construct a [[PipePair]] from the given [[AbstractSymmetricPipePair]].
   */
  def create[Above <: AnyRef, Below <: AnyRef] //
  (ctx: PipelineContext, ap: AbstractSymmetricPipePair[Above, Below]): SymmetricPipePair[Above, Below] =
    new Converter(ap, ctx) with SymmetricPipePair[Above, Below] {
      override val commandPipeline = { cmd: Above ⇒ normalize(ap.onCommand(cmd)) }
      override val eventPipeline = { evt: Below ⇒ normalize(ap.onEvent(evt)) }
      override val managementPort: Mgmt = { case x ⇒ normalize(ap.onManagementCommand(x)) }
    }
}

case class PipelinePorts[CmdAbove, CmdBelow, EvtAbove, EvtBelow](
  commands: CmdAbove ⇒ (Iterable[EvtAbove], Iterable[CmdBelow]),
  events: EvtBelow ⇒ (Iterable[EvtAbove], Iterable[CmdBelow]),
  management: PartialFunction[AnyRef, (Iterable[EvtAbove], Iterable[CmdBelow])])

/**
 * This class contains static factory methods which turn a pipeline context
 * and a [[PipelineStage]] into readily usable pipelines.
 */
object PipelineFactory {

  /**
   * Scala API: build the pipeline and return a pair of functions representing
   * the command and event pipelines. Each function returns the commands and
   * events resulting from running the pipeline on the given input, where the
   * the sequence of events is the first element of the returned pair and the
   * sequence of commands the second element.
   *
   * Exceptions thrown by the pipeline stages will not be caught.
   *
   * @param ctx The context object for this pipeline
   * @param stage The (composite) pipeline stage from whcih to build the pipeline
   * @return a pair of command and event pipeline functions
   */
  def buildFunctionTriple[Ctx <: PipelineContext, CmdAbove, CmdBelow, EvtAbove, EvtBelow] //
  (ctx: Ctx, stage: PipelineStage[Ctx, CmdAbove, CmdBelow, EvtAbove, EvtBelow]) //
  : PipelinePorts[CmdAbove, CmdBelow, EvtAbove, EvtBelow] = {
    val pp = stage apply ctx
    val split: (Iterable[Either[EvtAbove, CmdBelow]]) ⇒ (Iterable[EvtAbove], Iterable[CmdBelow]) = { in ⇒
      if (in.isEmpty) (Nil, Nil)
      else if (in eq ctx.cmd) (Nil, Seq[CmdBelow](ctx.cmd(0)))
      else if (in eq ctx.evt) (Seq[EvtAbove](ctx.evt(0)), Nil)
      else {
        val cmds = Vector.newBuilder[CmdBelow]
        val evts = Vector.newBuilder[EvtAbove]
        in foreach {
          case Right(cmd) ⇒ cmds += cmd
          case Left(evt)  ⇒ evts += evt
        }
        (evts.result, cmds.result)
      }
    }
    PipelinePorts(pp.commandPipeline andThen split, pp.eventPipeline andThen split, pp.managementPort andThen split)
  }

  /**
   * Scala API: build the pipeline attaching the given command and event sinks
   * to its outputs. Exceptions thrown within the pipeline stages will abort
   * processing (i.e. will not be processed in following stages) but will be
   * caught and passed as [[scala.util.Failure]] into the respective sink.
   *
   * Exceptions thrown while processing management commands are not caught.
   *
   * @param ctx The context object for this pipeline
   * @param stage The (composite) pipeline stage from whcih to build the pipeline
   * @param commandSink The function to invoke for commands or command failures
   * @param eventSink The function to invoke for events or event failures
   * @return a handle for injecting events or commands into the pipeline
   */
  def buildWithSinkFunctions[Ctx <: PipelineContext, CmdAbove, CmdBelow, EvtAbove, EvtBelow] //
  (ctx: Ctx,
   stage: PipelineStage[Ctx, CmdAbove, CmdBelow, EvtAbove, EvtBelow])(
     commandSink: Try[CmdBelow] ⇒ Unit,
     eventSink: Try[EvtAbove] ⇒ Unit): PipelineInjector[CmdAbove, EvtBelow] =
    new PipelineInjector[CmdAbove, EvtBelow] {
      val pl = stage(ctx)
      override def injectCommand(cmd: CmdAbove): Unit = {
        Try(pl.commandPipeline(cmd)) match {
          case f: Failure[_] ⇒ commandSink(f.asInstanceOf[Try[CmdBelow]])
          case Success(out) ⇒
            if (out.isEmpty) () // nothing
            else if (out eq ctx.cmd) commandSink(Success(ctx.cmd(0)))
            else if (out eq ctx.evt) eventSink(Success(ctx.evt(0)))
            else out foreach {
              case Right(cmd) ⇒ commandSink(Success(cmd))
              case Left(evt)  ⇒ eventSink(Success(evt))
            }
        }
      }
      override def injectEvent(evt: EvtBelow): Unit = {
        Try(pl.eventPipeline(evt)) match {
          case f: Failure[_] ⇒ eventSink(f.asInstanceOf[Try[EvtAbove]])
          case Success(out) ⇒
            if (out.isEmpty) () // nothing
            else if (out eq ctx.cmd) commandSink(Success(ctx.cmd(0)))
            else if (out eq ctx.evt) eventSink(Success(ctx.evt(0)))
            else out foreach {
              case Right(cmd) ⇒ commandSink(Success(cmd))
              case Left(evt)  ⇒ eventSink(Success(evt))
            }
        }
      }
      override def managementCommand(cmd: AnyRef): Unit = {
        val out = pl.managementPort(cmd)
        if (out.isEmpty) () // nothing
        else if (out eq ctx.cmd) commandSink(Success(ctx.cmd(0)))
        else if (out eq ctx.evt) eventSink(Success(ctx.evt(0)))
        else out foreach {
          case Right(cmd) ⇒ commandSink(Success(cmd))
          case Left(evt)  ⇒ eventSink(Success(evt))
        }
      }
    }

  /**
   * Java API: build the pipeline attaching the given callback object to its
   * outputs. Exceptions thrown within the pipeline stages will abort
   * processing (i.e. will not be processed in following stages) but will be
   * caught and passed as [[scala.util.Failure]] into the respective sink.
   *
   * Exceptions thrown while processing management commands are not caught.
   *
   * @param ctx The context object for this pipeline
   * @param stage The (composite) pipeline stage from whcih to build the pipeline
   * @param callback The [[PipelineSink]] to attach to the built pipeline
   * @return a handle for injecting events or commands into the pipeline
   */
  def buildWithSink[Ctx <: PipelineContext, CmdAbove, CmdBelow, EvtAbove, EvtBelow] //
  (ctx: Ctx,
   stage: PipelineStage[Ctx, CmdAbove, CmdBelow, EvtAbove, EvtBelow],
   callback: PipelineSink[CmdBelow, EvtAbove]): PipelineInjector[CmdAbove, EvtBelow] =
    buildWithSinkFunctions[Ctx, CmdAbove, CmdBelow, EvtAbove, EvtBelow](ctx, stage)({
      case Failure(thr) ⇒ callback.onCommandFailure(thr)
      case Success(cmd) ⇒ callback.onCommand(cmd)
    }, {
      case Failure(thr) ⇒ callback.onEventFailure(thr)
      case Success(evt) ⇒ callback.onEvent(evt)
    })
}

/**
 * A handle for injecting commands and events into a pipeline. Commands travel
 * down (or to the right) through the stages, events travel in the opposite
 * direction.
 *
 * @see [[PipelineFactory#buildWithSinkFunctions]]
 * @see [[PipelineFactory#buildWithSink]]
 */
trait PipelineInjector[Cmd, Evt] {

  /**
   * Inject the given command into the connected pipeline.
   */
  @throws(classOf[Exception])
  def injectCommand(cmd: Cmd): Unit

  /**
   * Inject the given event into the connected pipeline.
   */
  @throws(classOf[Exception])
  def injectEvent(event: Evt): Unit

  /**
   * Send a management command to all stages (in an unspecified order).
   */
  @throws(classOf[Exception])
  def managementCommand(cmd: AnyRef): Unit
}

/**
 * A sink which can be attached by [[PipelineFactory#buildWithSink]] to a
 * pipeline when it is being built. The methods are called when commands,
 * events or their failures occur during evaluation of the pipeline (i.e.
 * when injection is triggered using the associated [[PipelineInjector]]).
 */
abstract class PipelineSink[Cmd, Evt] {

  /**
   * This callback is invoked for every command generated by the pipeline.
   *
   * By default this does nothing.
   */
  @throws(classOf[Throwable])
  def onCommand(cmd: Cmd): Unit = ()

  /**
   * This callback is invoked if an exception occurred while processing an
   * injected command. If this callback is invoked that no other callbacks will
   * be invoked for the same injection.
   *
   * By default this will just throw the exception.
   */
  @throws(classOf[Throwable])
  def onCommandFailure(thr: Throwable): Unit = throw thr

  /**
   * This callback is invoked for every event generated by the pipeline.
   *
   * By default this does nothing.
   */
  @throws(classOf[Throwable])
  def onEvent(event: Evt): Unit = ()

  /**
   * This callback is invoked if an exception occurred while processing an
   * injected event. If this callback is invoked that no other callbacks will
   * be invoked for the same injection.
   *
   * By default this will just throw the exception.
   */
  @throws(classOf[Throwable])
  def onEventFailure(thr: Throwable): Unit = throw thr
}

/**
 * This base trait of each pipeline’s context provides optimized facilities
 * for generating single commands or events (i.e. the fast common case of 1:1
 * message transformations).
 *
 * <b>IMPORTANT NOTICE:</b>
 *
 * A PipelineContext MUST NOT be shared between multiple pipelines, it contains mutable
 * state without synchronization. You have been warned!
 *
 * @see AbstractPipelineContext see AbstractPipelineContext for a default implementation (Java)
 */
trait PipelineContext {

  /**
   * INTERNAL API: do not touch!
   */
  private val cmdHolder = new Array[AnyRef](1)
  /**
   * INTERNAL API: do not touch!
   */
  private val evtHolder = new Array[AnyRef](1)
  /**
   * INTERNAL API: do not touch!
   */
  private[io] val cmd = WrappedArray.make(cmdHolder)
  /**
   * INTERNAL API: do not touch!
   */
  private[io] val evt = WrappedArray.make(evtHolder)

  /**
   * Scala API: Wrap a single command for efficient return to the pipeline’s machinery.
   * This method avoids allocating a [[Right]] and an [[Iterable]] by reusing
   * one such instance within the PipelineContext, hence it can be used ONLY ONCE by
   * each pipeline stage. Prototypic and safe usage looks like this:
   *
   * {{{
   * override val commandPipeline = { cmd =>
   *   val myResult = ...
   *   ctx.singleCommand(myResult)
   * }
   * }}}
   *
   * @see AbstractPipePair#singleCommand see AbstractPipePair for the Java API
   */
  def singleCommand[Cmd <: AnyRef, Evt <: AnyRef](cmd: Cmd): Iterable[Either[Evt, Cmd]] = {
    cmdHolder(0) = cmd
    this.cmd
  }

  /**
   * Scala API: Wrap a single event for efficient return to the pipeline’s machinery.
   * This method avoids allocating a [[Left]] and an [[Iterable]] by reusing
   * one such instance within the context, hence it can be used ONLY ONCE by
   * each pipeline stage. Prototypic and safe usage looks like this:
   *
   * {{{
   * override val eventPipeline = { cmd =>
   *   val myResult = ...
   *   ctx.singleEvent(myResult)
   * }
   * }}}
   *
   * @see AbstractPipePair#singleEvent see AbstractPipePair for the Java API
   */
  def singleEvent[Cmd <: AnyRef, Evt <: AnyRef](evt: Evt): Iterable[Either[Evt, Cmd]] = {
    evtHolder(0) = evt
    this.evt
  }

  /**
   * A shared (and shareable) instance of an empty `Iterable[Either[EvtAbove, CmdBelow]]`.
   * Use this when processing does not yield any commands or events as result.
   */
  def nothing[Cmd, Evt]: Iterable[Either[Evt, Cmd]] = Nil

  /**
   * INTERNAL API: Dealias a possibly optimized return value such that it can
   * be safely used; this is never needed when only using public API.
   */
  def dealias[Cmd, Evt](msg: Iterable[Either[Evt, Cmd]]): Iterable[Either[Evt, Cmd]] = {
    if (msg.isEmpty) Nil
    else if (msg eq cmd) Seq(Right(cmd(0)))
    else if (msg eq evt) Seq(Left(evt(0)))
    else msg
  }
}

/**
 * This base trait of each pipeline’s context provides optimized facilities
 * for generating single commands or events (i.e. the fast common case of 1:1
 * message transformations).
 *
 * <b>IMPORTANT NOTICE:</b>
 *
 * A PipelineContext MUST NOT be shared between multiple pipelines, it contains mutable
 * state without synchronization. You have been warned!
 */
abstract class AbstractPipelineContext extends PipelineContext

object PipelineStage {

  /**
   * Java API: attach the two given stages such that the command output of the
   * first is fed into the command input of the second, and the event output of
   * the second is fed into the event input of the first. In other words:
   * sequence the stages such that the left one is on top of the right one.
   *
   * @param left the left or upper pipeline stage
   * @param right the right or lower pipeline stage
   * @return a pipeline stage representing the sequence of the two stages
   */
  def sequence[Ctx <: PipelineContext, CmdAbove, CmdBelow, CmdBelowBelow, EvtAbove, EvtBelow, EvtBelowBelow] //
  (left: PipelineStage[_ >: Ctx, CmdAbove, CmdBelow, EvtAbove, EvtBelow],
   right: PipelineStage[_ >: Ctx, CmdBelow, CmdBelowBelow, EvtBelow, EvtBelowBelow]) //
   : PipelineStage[Ctx, CmdAbove, CmdBelowBelow, EvtAbove, EvtBelowBelow] =
    left >> right

  /**
   * Java API: combine the two stages such that the command pipeline of the
   * left stage is used and the event pipeline of the right, discarding the
   * other two sub-pipelines.
   *
   * @param left the command pipeline
   * @param right the event pipeline
   * @return a pipeline stage using the left command pipeline and the right event pipeline
   */
  def combine[Ctx <: PipelineContext, CmdAbove, CmdBelow, EvtAbove, EvtBelow] //
  (left: PipelineStage[Ctx, CmdAbove, CmdBelow, EvtAbove, EvtBelow],
   right: PipelineStage[Ctx, CmdAbove, CmdBelow, EvtAbove, EvtBelow]) //
   : PipelineStage[Ctx, CmdAbove, CmdBelow, EvtAbove, EvtBelow] =
    left | right
}

/**
 * A [[PipelineStage]] which is symmetric in command and event types, i.e. it only
 * has one command and event type above and one below.
 */
abstract class SymmetricPipelineStage[Context <: PipelineContext, Above, Below] extends PipelineStage[Context, Above, Below, Above, Below]

/**
 * A pipeline stage which can be combined with other stages to build a
 * protocol stack. The main function of this class is to serve as a factory
 * for the actual [[PipePair]] generated by the [[#apply]] method so that a
 * context object can be passed in.
 *
 * @see [[PipelineFactory]]
 */
abstract class PipelineStage[Context <: PipelineContext, CmdAbove, CmdBelow, EvtAbove, EvtBelow] { left ⇒

  /**
   * Implement this method to generate this stage’s pair of command and event
   * functions.
   *
   * INTERNAL API: do not use this method to instantiate a pipeline!
   *
   * @see [[PipelineFactory]]
   * @see [[AbstractPipePair]]
   * @see [[AbstractSymmetricPipePair]]
   */
  protected[io] def apply(ctx: Context): PipePair[CmdAbove, CmdBelow, EvtAbove, EvtBelow]

  /**
   * Scala API: attach the two given stages such that the command output of the
   * first is fed into the command input of the second, and the event output of
   * the second is fed into the event input of the first. In other words:
   * sequence the stages such that the left one is on top of the right one.
   *
   * @param right the right or lower pipeline stage
   * @return a pipeline stage representing the sequence of the two stages
   */
  def >>[CmdBelowBelow, EvtBelowBelow, BelowContext <: Context] //
  (right: PipelineStage[_ >: BelowContext, CmdBelow, CmdBelowBelow, EvtBelow, EvtBelowBelow]) //
  : PipelineStage[BelowContext, CmdAbove, CmdBelowBelow, EvtAbove, EvtBelowBelow] =
    new PipelineStage[BelowContext, CmdAbove, CmdBelowBelow, EvtAbove, EvtBelowBelow] {

      protected[io] override def apply(ctx: BelowContext): PipePair[CmdAbove, CmdBelowBelow, EvtAbove, EvtBelowBelow] = {

        val leftPL = left(ctx)
        val rightPL = right(ctx)

        new PipePair[CmdAbove, CmdBelowBelow, EvtAbove, EvtBelowBelow] {

          type Output = Either[EvtAbove, CmdBelowBelow]

          import language.implicitConversions
          @inline implicit def narrowRight[A, B, C](in: Right[A, B]): Right[C, B] = in.asInstanceOf[Right[C, B]]
          @inline implicit def narrowLeft[A, B, C](in: Left[A, B]): Left[A, C] = in.asInstanceOf[Left[A, C]]

          def loopLeft(input: Iterable[Either[EvtAbove, CmdBelow]]): Iterable[Output] = {
            if (input.isEmpty) Nil
            else if (input eq ctx.cmd) loopRight(rightPL.commandPipeline(ctx.cmd(0)))
            else if (input eq ctx.evt) ctx.evt
            else {
              val output = Vector.newBuilder[Output]
              input foreach {
                case Right(cmd)  ⇒ output ++= ctx.dealias(loopRight(rightPL.commandPipeline(cmd)))
                case l @ Left(_) ⇒ output += l
              }
              output.result
            }
          }

          def loopRight(input: Iterable[Either[EvtBelow, CmdBelowBelow]]): Iterable[Output] = {
            if (input.isEmpty) Nil
            else if (input eq ctx.cmd) ctx.cmd
            else if (input eq ctx.evt) loopLeft(leftPL.eventPipeline(ctx.evt(0)))
            else {
              val output = Vector.newBuilder[Output]
              input foreach {
                case r @ Right(_) ⇒ output += r
                case Left(evt)    ⇒ output ++= ctx.dealias(loopLeft(leftPL.eventPipeline(evt)))
              }
              output.result
            }
          }

          override val commandPipeline = { a: CmdAbove ⇒ loopLeft(leftPL.commandPipeline(a)) }

          override val eventPipeline = { b: EvtBelowBelow ⇒ loopRight(rightPL.eventPipeline(b)) }

          override val managementPort: PartialFunction[AnyRef, Iterable[Either[EvtAbove, CmdBelowBelow]]] = {
            case x ⇒
              val output = Vector.newBuilder[Output]
              output ++= ctx.dealias(loopLeft(leftPL.managementPort.applyOrElse(x, (_: AnyRef) ⇒ Nil)))
              output ++= ctx.dealias(loopRight(rightPL.managementPort.applyOrElse(x, (_: AnyRef) ⇒ Nil)))
              output.result
          }
        }
      }
    }

  /**
   * Scala API: combine the two stages such that the command pipeline of the
   * left stage is used and the event pipeline of the right, discarding the
   * other two sub-pipelines.
   *
   * @param right the event pipeline
   * @return a pipeline stage using the left command pipeline and the right event pipeline
   */
  def |[RightContext <: Context] //
  (right: PipelineStage[_ >: RightContext, CmdAbove, CmdBelow, EvtAbove, EvtBelow]) //
  : PipelineStage[RightContext, CmdAbove, CmdBelow, EvtAbove, EvtBelow] =
    new PipelineStage[RightContext, CmdAbove, CmdBelow, EvtAbove, EvtBelow] {
      override def apply(ctx: RightContext): PipePair[CmdAbove, CmdBelow, EvtAbove, EvtBelow] =
        new PipePair[CmdAbove, CmdBelow, EvtAbove, EvtBelow] {

          val leftPL = left(ctx)
          val rightPL = right(ctx)

          override val commandPipeline = leftPL.commandPipeline
          override val eventPipeline = rightPL.eventPipeline
          override val managementPort: Mgmt = {
            case x ⇒
              val output = Vector.newBuilder[Either[EvtAbove, CmdBelow]]
              output ++= ctx.dealias(leftPL.managementPort(x))
              output ++= ctx.dealias(rightPL.managementPort(x))
              output.result
          }
        }
    }
}

//#length-field-frame
/**
 * Pipeline stage for length-field encoded framing. It will prepend a
 * four-byte length header to the message; the header contains the length of
 * the resulting frame including header in big-endian representation.
 *
 * The `maxSize` argument is used to protect the communication channel sanity:
 * larger frames will not be sent (silently dropped) or received (in which case
 * stream decoding would be broken, hence throwing an IllegalArgumentException).
 */
class LengthFieldFrame(maxSize: Int,
                       byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
                       headerSize: Int = 4,
                       lengthIncludesHeader: Boolean = true)
  extends SymmetricPipelineStage[PipelineContext, ByteString, ByteString] {

  //#range-checks-omitted
  require(byteOrder ne null, "byteOrder must not be null")
  require(headerSize > 0 && headerSize <= 4, "headerSize must be in (0, 4]")
  require(maxSize > 0, "maxSize must be positive")
  require(maxSize <= (Int.MaxValue >> (4 - headerSize) * 8) * (if (headerSize == 4) 1 else 2),
    "maxSize cannot exceed 256**headerSize")
  //#range-checks-omitted

  override def apply(ctx: PipelineContext) =
    new SymmetricPipePair[ByteString, ByteString] {
      var buffer = None: Option[ByteString]
      implicit val byteOrder = LengthFieldFrame.this.byteOrder

      /**
       * Extract as many complete frames as possible from the given ByteString
       * and return the remainder together with the extracted frames in reverse
       * order.
       */
      @tailrec
      def extractFrames(bs: ByteString, acc: List[ByteString]) //
      : (Option[ByteString], Seq[ByteString]) = {
        if (bs.isEmpty) {
          (None, acc)
        } else if (bs.length < headerSize) {
          (Some(bs.compact), acc)
        } else {
          val length = bs.iterator.getLongPart(headerSize).toInt
          if (length < 0 || length > maxSize)
            throw new IllegalArgumentException(
              s"received too large frame of size $length (max = $maxSize)")
          val total = if (lengthIncludesHeader) length else length + headerSize
          if (bs.length >= total) {
            extractFrames(bs drop total, bs.slice(headerSize, total) :: acc)
          } else {
            (Some(bs.compact), acc)
          }
        }
      }

      /*
       * This is how commands (writes) are transformed: calculate length
       * including header, write that to a ByteStringBuilder and append the
       * payload data. The result is a single command (i.e. `Right(...)`).
       */
      override def commandPipeline =
        { bs: ByteString ⇒
          val length =
            if (lengthIncludesHeader) bs.length + headerSize else bs.length
          if (length > maxSize) Seq()
          else {
            val bb = ByteString.newBuilder
            bb.putLongPart(length, headerSize)
            bb ++= bs
            ctx.singleCommand(bb.result)
          }
        }

      /*
       * This is how events (reads) are transformed: append the received
       * ByteString to the buffer (if any) and extract the frames from the
       * result. In the end store the new buffer contents and return the
       * list of events (i.e. `Left(...)`).
       */
      override def eventPipeline =
        { bs: ByteString ⇒
          val data = if (buffer.isEmpty) bs else buffer.get ++ bs
          val (nb, frames) = extractFrames(data, Nil)
          buffer = nb
          /*
           * please note the specialized (optimized) facility for emitting
           * just a single event
           */
          frames match {
            case Nil        ⇒ Nil
            case one :: Nil ⇒ ctx.singleEvent(one)
            case many       ⇒ many reverseMap (Left(_))
          }
        }
    }
}
//#length-field-frame

/**
 * Pipeline stage for delimiter byte based framing and de-framing. Useful for string oriented protocol using '\n'
 * or 0 as delimiter values.
 *
 * @param maxSize The maximum size of the frame the pipeline is willing to decode. Not checked for encoding, as the
 *                sender might decide to pass through multiple chunks in one go (multiple lines in case of a line-based
 *                protocol)
 * @param delimiter The sequence of bytes that will be used as the delimiter for decoding.
 * @param includeDelimiter If enabled, the delmiter bytes will be part of the decoded messages. In the case of sends
 *                          the delimiter has to be appended to the end of frames by the user. It is also possible
 *                          to send multiple frames by embedding multiple delimiters in the passed ByteString
 */
class DelimiterFraming(maxSize: Int, delimiter: ByteString = ByteString('\n'), includeDelimiter: Boolean = false)
  extends SymmetricPipelineStage[PipelineContext, ByteString, ByteString] {

  require(maxSize > 0, "maxSize must be positive")
  require(delimiter.nonEmpty, "delimiter must not be empty")

  override def apply(ctx: PipelineContext) = new SymmetricPipePair[ByteString, ByteString] {
    val singleByteDelimiter: Boolean = delimiter.size == 1
    var buffer: ByteString = ByteString.empty
    var delimiterFragment: Option[ByteString] = None
    val firstByteOfDelimiter = delimiter.head

    @tailrec
    private def extractParts(nextChunk: ByteString, acc: List[ByteString]): List[ByteString] = delimiterFragment match {
      case Some(fragment) if nextChunk.size < fragment.size && fragment.startsWith(nextChunk) ⇒
        buffer ++= nextChunk
        delimiterFragment = Some(fragment.drop(nextChunk.size))
        acc
      // We got the missing parts of the delimiter
      case Some(fragment) if nextChunk.startsWith(fragment) ⇒
        val decoded = if (includeDelimiter) buffer ++ fragment else buffer.take(buffer.size - delimiter.size + fragment.size)
        buffer = ByteString.empty
        delimiterFragment = None
        extractParts(nextChunk.drop(fragment.size), decoded :: acc)
      case _ ⇒
        val matchPosition = nextChunk.indexOf(firstByteOfDelimiter)
        if (matchPosition == -1) {
          delimiterFragment = None
          val minSize = buffer.size + nextChunk.size
          if (minSize > maxSize) throw new IllegalArgumentException(
            s"Received too large frame of size $minSize (max = $maxSize)")
          buffer ++= nextChunk
          acc
        } else if (matchPosition + delimiter.size > nextChunk.size) {
          val delimiterMatchLength = nextChunk.size - matchPosition
          if (nextChunk.drop(matchPosition) == delimiter.take(delimiterMatchLength)) {
            buffer ++= nextChunk
            // we are expecting the other parts of the delimiter
            delimiterFragment = Some(delimiter.drop(nextChunk.size - matchPosition))
            acc
          } else {
            // false positive
            delimiterFragment = None
            buffer ++= nextChunk.take(matchPosition + 1)
            extractParts(nextChunk.drop(matchPosition + 1), acc)
          }
        } else {
          delimiterFragment = None
          val missingBytes: Int = if (includeDelimiter) matchPosition + delimiter.size else matchPosition
          val expectedSize = buffer.size + missingBytes
          if (expectedSize > maxSize) throw new IllegalArgumentException(
            s"Received frame already of size $expectedSize (max = $maxSize)")

          if (singleByteDelimiter || nextChunk.slice(matchPosition, matchPosition + delimiter.size) == delimiter) {
            val decoded = buffer ++ nextChunk.take(missingBytes)
            buffer = ByteString.empty
            extractParts(nextChunk.drop(matchPosition + delimiter.size), decoded :: acc)
          } else {
            buffer ++= nextChunk.take(matchPosition + 1)
            extractParts(nextChunk.drop(matchPosition + 1), acc)
          }
        }

    }

    override val eventPipeline = {
      bs: ByteString ⇒
        val parts = extractParts(bs, Nil)
        buffer = buffer.compact // TODO: This should be properly benchmarked and memory profiled
        parts match {
          case Nil        ⇒ Nil
          case one :: Nil ⇒ ctx.singleEvent(one.compact)
          case many       ⇒ many reverseMap { frame ⇒ Left(frame.compact) }
        }
    }

    override val commandPipeline = {
      bs: ByteString ⇒ ctx.singleCommand(bs)
    }
  }
}

/**
 * Simple convenience pipeline stage for turning Strings into ByteStrings and vice versa.
 *
 * @param charset The character set to be used for encoding and decoding the raw byte representation of the strings.
 */
class StringByteStringAdapter(charset: String = "utf-8")
  extends PipelineStage[PipelineContext, String, ByteString, String, ByteString] {

  override def apply(ctx: PipelineContext) = new PipePair[String, ByteString, String, ByteString] {

    val commandPipeline = (str: String) ⇒ ctx.singleCommand(ByteString(str, charset))

    val eventPipeline = (bs: ByteString) ⇒ ctx.singleEvent(bs.decodeString(charset))
  }
}

/**
 * This trait expresses that the pipeline’s context needs to provide a logging
 * facility.
 */
trait HasLogging extends PipelineContext {
  /**
   * Retrieve the [[LoggingAdapter]] for this pipeline’s context.
   */
  def getLogger: LoggingAdapter
}

//#tick-generator
/**
 * This trait expresses that the pipeline’s context needs to live within an
 * actor and provide its ActorContext.
 */
trait HasActorContext extends PipelineContext {
  /**
   * Retrieve the [[ActorContext]] for this pipeline’s context.
   */
  def getContext: ActorContext
}

object TickGenerator {
  /**
   * This message type is used by the TickGenerator to trigger
   * the rescheduling of the next Tick. The actor hosting the pipeline
   * which includes a TickGenerator must arrange for messages of this
   * type to be injected into the management port of the pipeline.
   */
  trait Trigger

  /**
   * This message type is emitted by the TickGenerator to the whole
   * pipeline, informing all stages about the time at which this Tick
   * was emitted (relative to some arbitrary epoch).
   */
  case class Tick(@BeanProperty timestamp: FiniteDuration) extends Trigger
}

/**
 * This pipeline stage does not alter the events or commands
 */
class TickGenerator[Cmd <: AnyRef, Evt <: AnyRef](interval: FiniteDuration)
  extends PipelineStage[HasActorContext, Cmd, Cmd, Evt, Evt] {
  import TickGenerator._

  override def apply(ctx: HasActorContext) =
    new PipePair[Cmd, Cmd, Evt, Evt] {

      // use unique object to avoid double-activation on actor restart
      private val trigger: Trigger =
        new Trigger {
          override def toString = s"Tick[${ctx.getContext.self.path}]"
        }

      private def schedule() =
        ctx.getContext.system.scheduler.scheduleOnce(
          interval, ctx.getContext.self, trigger)(ctx.getContext.dispatcher)

      // automatically activate this generator
      schedule()

      override val commandPipeline = (cmd: Cmd) ⇒ ctx.singleCommand(cmd)

      override val eventPipeline = (evt: Evt) ⇒ ctx.singleEvent(evt)

      override val managementPort: Mgmt = {
        case `trigger` ⇒
          ctx.getContext.self ! Tick(Deadline.now.time)
          schedule()
          Nil
      }
    }
}
//#tick-generator

