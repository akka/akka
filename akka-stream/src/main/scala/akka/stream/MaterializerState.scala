/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import akka.actor.{ ActorPath, ActorRef }
import akka.annotation.{ ApiMayChange, DoNotInherit, InternalApi }
import akka.stream.impl.{ PhasedFusingActorMaterializer, StreamSupervisor }
import akka.pattern.ask
import akka.stream.impl.fusing.ActorGraphInterpreter
import akka.util.Timeout

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._

/**
 * Debug utility to dump the running streams of a materializers in a structure describing the graph layout
 * and "waits-on" relationships.
 *
 * Some of the data extracted may be off unless the stream has settled, for example in when deadlocked, but the
 * structure should be valid regardless. Extracting the information often will have an impact on the performance
 * of the running streams.
 */
object MaterializerState {

  def streamSnapshots(mat: Materializer): Future[Seq[StreamSnapshot]] = {
    mat match {
      case impl: PhasedFusingActorMaterializer ⇒
        import impl.system.dispatcher
        requestFromSupervisor(impl.supervisor)
    }
  }

  /** INTERNAL API */
  @InternalApi
  private[akka] def requestFromSupervisor(supervisor: ActorRef)(implicit ec: ExecutionContext): Future[Seq[StreamSnapshot]] = {
    // FIXME arbitrary timeout
    implicit val timeout: Timeout = 10.seconds
    (supervisor ? StreamSupervisor.GetChildren)
      .mapTo[StreamSupervisor.Children]
      .flatMap(msg ⇒
        Future.sequence(msg.children.toSeq.map(requestFromChild))
      )
  }

  /** INTERNAL API */
  @InternalApi
  private[akka] def requestFromChild(child: ActorRef)(implicit ec: ExecutionContext): Future[StreamSnapshot] = {
    // FIXME arbitrary timeout
    implicit val timeout: Timeout = 10.seconds
    (child ? ActorGraphInterpreter.Snapshot).mapTo[StreamSnapshot]
  }

}

/**
 * A snapshot of the materializer state
 * @param streams The streams that was materialized with the materializer and were running when the snapshot was taken.
 */
@ApiMayChange
final case class MaterializerState(streams: Seq[StreamSnapshot])

/**
 * A snapshot of one running stream
 */
@DoNotInherit @ApiMayChange
sealed trait StreamSnapshot {
  /**
   * Running interpreters
   */
  // FIXME why doesn't this then contain only RunningIterpreters ?
  def activeInterpreters: Seq[RunningInterpreter]

  /**
   * Interpreters that has been created but not yet initialized
   *
   */
  // FIXME why doesn't this then contain only UnitializedInterpreters ?
  def newShells: Seq[UninitializedInterpreter]
}

/**
 * INTERNAL API
 */
@InternalApi
final private[akka] case class StreamSnapshotImpl(
  self:               ActorPath,
  activeInterpreters: Seq[RunningInterpreter],
  newShells:          Seq[UninitializedInterpreter]) extends StreamSnapshot

/**
 * A snapshot of one interpreter - contains a set of logics running in the same underlying actor. Note that
 * multiple interpreters may be running in the same actor (because of submaterialization)
 */
@DoNotInherit @ApiMayChange
sealed trait InterpreterSnapshot {
  def logics: Seq[LogicSnapshot]
}

/**
 * A stream interpreter that was not yet initialized when the snapshot was taken
 */
final case class UninitializedInterpreter(
  logics: Seq[LogicSnapshot]) extends InterpreterSnapshot

@DoNotInherit @ApiMayChange
sealed trait RunningInterpreter extends InterpreterSnapshot {
  def logics: Seq[LogicSnapshot]
  def connections: Seq[ConnectionSnapshot]
  def runningLogicsCount: Int
  def stoppedLogics: Seq[LogicSnapshot]
}

@InternalApi
private[akka] final case class RunningInterpreterImpl(
  logics:             Seq[LogicSnapshot],
  connections:        Seq[ConnectionSnapshot],
  queueStatus:        String,
  runningLogicsCount: Int,
  stoppedLogics:      Seq[LogicSnapshot]) extends RunningInterpreter

@ApiMayChange
final case class LogicSnapshot(index: Int, label: String, attributes: Attributes)

@ApiMayChange
object ConnectionSnapshot {
  sealed trait ConnectionState
  case object ShouldPull extends ConnectionState
  case object ShouldPush extends ConnectionState
  case object Closed extends ConnectionState
}

@ApiMayChange
final case class ConnectionSnapshot(
  id:    Int,
  in:    LogicSnapshot,
  out:   LogicSnapshot,
  state: ConnectionSnapshot.ConnectionState)

