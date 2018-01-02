/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.remote.impl

import akka.actor.{ Actor, ActorRef, ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider, Props }
import akka.stream.ActorMaterializerHelper
import akka.stream.impl.SeqActorName
import akka.stream.remote.impl.StreamRefsMasterActor.AllocatePusherToRemoteSink
import akka.stream.remote.scaladsl.{ SinkRef, StreamRefSettings }

object StreamRefsMaster extends ExtensionId[StreamRefsMaster] with ExtensionIdProvider {

  override def createExtension(system: ExtendedActorSystem): StreamRefsMaster =
    new StreamRefsMaster(system)

  override def lookup(): StreamRefsMaster.type = this

  override def get(system: ActorSystem): StreamRefsMaster = super.get(system)
}

/** INTERNAL API */
private[stream] final class StreamRefsMaster(system: ExtendedActorSystem) extends Extension {

  val settings: StreamRefSettings = new StreamRefSettings(system.settings.config)

  private[this] val sourceRefOriginSinkNames = SeqActorName("SourceRefOriginSink") // "local origin"
  private[this] val sourceRefNames = SeqActorName("SourceRef") // "remote receiver"

  private[this] val sinkRefTargetSourceNames = SeqActorName("SinkRefTargetSource") // "local target"
  private[this] val sinkRefNames = SeqActorName("SinkRef") // "remote sender"

  // TODO do we need it? perhaps for reaping?
  // system.systemActorOf(StreamRefsMasterActor.props(), "streamRefsMaster")

  def nextSinkRefTargetSourceName(): String =
    sinkRefTargetSourceNames.next()

  def nextSinkRefName(): String =
    sinkRefNames.next()

  def nextSourceRefOriginSinkName(): String =
    sourceRefOriginSinkNames.next()

  def nextSourceRefName(): String =
    sourceRefNames.next()

}

object StreamRefsMasterActor {
  def props(): Props = Props(new StreamRefsMasterActor())

  final case class AllocatePusherToRemoteSink(stageRef: ActorRef)
}

class StreamRefsMasterActor extends Actor {
  override def receive: Receive = {
    case AllocatePusherToRemoteSink(stageRef) ⇒
    //      context.actorOf()
  }
}
