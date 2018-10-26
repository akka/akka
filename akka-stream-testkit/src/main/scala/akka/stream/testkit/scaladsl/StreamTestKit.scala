/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.testkit.scaladsl

import akka.actor.{ ActorRef, ActorSystem }
import akka.annotation.InternalApi
import akka.stream._
import akka.stream.impl.{ PhasedFusingActorMaterializer, StreamSupervisor }
import akka.testkit.TestProbe
import akka.util.Timeout

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext }

object StreamTestKit {

  /**
   * Asserts that after the given code block is ran, no stages are left over
   * that were created by the given materializer.
   *
   * This assertion is useful to check that all of the stages have
   * terminated successfully.
   */
  def assertAllStagesStopped[T](block: ⇒ T)(implicit materializer: Materializer): T =
    materializer match {
      case impl: PhasedFusingActorMaterializer ⇒
        stopAllChildren(impl.system, impl.supervisor)
        val result = block
        assertNoChildren(impl.system, impl.supervisor)
        result
      case _ ⇒ block
    }

  /** INTERNAL API */
  @InternalApi private[testkit] def stopAllChildren(sys: ActorSystem, supervisor: ActorRef): Unit = {
    val probe = TestProbe()(sys)
    probe.send(supervisor, StreamSupervisor.StopChildren)
    probe.expectMsg(StreamSupervisor.StoppedChildren)
  }

  /** INTERNAL API */
  @InternalApi private[testkit] def assertNoChildren(sys: ActorSystem, supervisor: ActorRef): Unit = {
    val probe = TestProbe()(sys)
    probe.within(5.seconds) {
      try probe.awaitAssert {
        supervisor.tell(StreamSupervisor.GetChildren, probe.ref)
        val children = probe.expectMsgType[StreamSupervisor.Children].children
        assert(
          children.isEmpty,
          s"expected no StreamSupervisor children, but got [${children.mkString(", ")}]")
      } catch {
        case ex: Throwable ⇒
          import sys.dispatcher
          printDebugDump(supervisor)
          throw ex
      }
    }
  }

  /** INTERNAL API */
  @InternalApi private[testkit] def printDebugDump(streamSupervisor: ActorRef)(implicit ec: ExecutionContext): Unit = {
    // FIXME arbitrary timeouts here
    implicit val timeout: Timeout = 3.seconds
    val doneDumping = MaterializerSnapshot.requestFromSupervisor(streamSupervisor)
      .map(snapshots ⇒
        snapshots.foreach(s ⇒ println(snapshotString(s))
        ))
    Await.result(doneDumping, 5.seconds)
  }

  /** INTERNAL API */
  @InternalApi private[testkit] def snapshotString(snapshot: StreamSnapshot): String = {
    val builder = StringBuilder.newBuilder
    builder.append(s"activeShells (actor: ${snapshot.self}):\n")
    snapshot.activeInterpreters.foreach { shell ⇒
      builder.append("  ")
      appendShellSnapshot(builder, shell)
      builder.append("\n")
      shell.interpreter match {
        case Some(interpreter) ⇒ appendInterpreterSnapshot(builder, interpreter)
        case None              ⇒ builder.append("    Not initialized")
      }
      builder.append("\n")
    }
    builder.append(s"newShells:\n")
    snapshot.newShells.foreach { shell ⇒
      builder.append("  ")
      appendShellSnapshot(builder, shell)
      builder.append("\n")
      shell.interpreter match {
        case Some(interpreter) ⇒ appendInterpreterSnapshot(builder, interpreter)
        case None              ⇒ builder.append("    Not initialized")
      }
      builder.append("\n")
    }
    builder.toString
  }

  private def appendShellSnapshot(builder: StringBuilder, shell: GraphInterpreterShellSnapshot): Unit = {
    builder.append("GraphInterpreterShell(\n  logics: [\n")
    val logicsToPrint = shell.interpreter match {
      case Some(interpreter) ⇒ interpreter.logics
      case None              ⇒ shell.logics
    }
    logicsToPrint.foreach { logic ⇒
      builder.append("    ")
        .append(logic.label)
        .append(" attrs: [")
        .append(logic.attributes.attributeList.mkString(", "))
        .append("],\n")
    }
    builder.setLength(builder.length - 2)
    shell.interpreter match {
      case Some(interpreter) ⇒
        builder.append("\n  ],\n  connections: [\n")
        interpreter.connections.foreach { connection ⇒
          builder.append("    ")
            .append("Connection(")
            .append(connection.id)
            .append(", ")
            .append(connection.in.label)
            .append(", ")
            .append(connection.out.label)
            .append(", ")
            .append(connection.state)
            .append(")\n")
        }
        builder.setLength(builder.length - 2)

      case None ⇒
    }
    builder.append("\n  ]\n)")
    builder.toString()
  }

  private def appendInterpreterSnapshot(builder: StringBuilder, snapshot: InterpreterSnapshot): Unit = {
    try {
      builder.append("\ndot format graph for deadlock analysis:\n")
      builder.append("================================================================\n")
      builder.append("digraph waits {\n")

      for (i ← snapshot.logics.indices) {
        val logic = snapshot.logics(i)
        builder.append(s"""  N$i [label="${logic.label}"];""").append('\n')
      }

      for (connection ← snapshot.connections) {
        val inName = "N" + connection.in.index
        val outName = "N" + connection.out.index

        builder.append(s"  $inName -> $outName ")
        connection.state match {
          case ConnectionSnapshot.ShouldPull ⇒
            builder.append("[label=shouldPull, color=blue];")
          case ConnectionSnapshot.ShouldPush ⇒
            builder.append(s"[label=shouldPush, color=red];")
          case ConnectionSnapshot.Closed ⇒
            builder.append("[style=dotted, label=closed, dir=both];")
          case _ ⇒
        }
        builder.append("\n")
      }

      builder.append("}\n================================================================\n")
      builder.append(s"// ${snapshot.queueStatus} (running=${snapshot.runningLogics}, shutdown=${snapshot.stoppedLogics.mkString(",")})")
      builder.toString()
    } catch {
      case _: NoSuchElementException ⇒ "Not all logics has a stage listed, cannot create graph"
    }
  }

}

