/**
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
object MaterializerSnapshot {

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

@ApiMayChange
final case class MaterializerSnapshot(streams: Seq[StreamSnapshot])

@ApiMayChange
final case class StreamSnapshot(
  // FIXME too easy to abuse if we make it this easy to interact with the stream actors?
  self:               ActorPath,
  activeInterpreters: Seq[GraphInterpreterShellSnapshot],
  newShells:          Seq[GraphInterpreterShellSnapshot])

final case class GraphInterpreterShellSnapshot(
  logics:      Seq[LogicSnapshot],
  interpreter: Option[InterpreterSnapshot])

@ApiMayChange
final case class InterpreterSnapshot(
  logics:      Seq[LogicSnapshot],
  connections: Seq[ConnectionSnapshot],
  // FIXME better type?
  queueStatus:   String,
  runningLogics: Int,
  stoppedLogics: List[Int])

// do we need both of these
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

