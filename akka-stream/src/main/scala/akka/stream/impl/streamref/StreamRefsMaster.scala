/**
 * Copyright (C) 2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl.streamref

import akka.actor.{ Actor, ActorLogging, ActorPath, ActorRef, ActorRefProvider, ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider, MinimalActorRef, Props, RepointableActorRef, Stash }
import akka.annotation.InternalApi
import akka.event.Logging
import akka.stream.SourceRef
import akka.stream.impl.SeqActorName

import scala.concurrent.Future

/** INTERNAL API */
@InternalApi
private[stream] object StreamRefsMaster extends ExtensionId[StreamRefsMaster] with ExtensionIdProvider {

  override def createExtension(system: ExtendedActorSystem): StreamRefsMaster =
    new StreamRefsMaster(system)

  override def lookup(): StreamRefsMaster.type = this

  override def get(system: ActorSystem): StreamRefsMaster = super.get(system)
}

/** INTERNAL API */
@InternalApi
private[stream] final class StreamRefsMaster(system: ExtendedActorSystem) extends Extension {

  private val log = Logging(system, getClass)

  private[this] val sourceRefStageNames = SeqActorName("SourceRef") // "local target"
  private[this] val sinkRefStageNames = SeqActorName("SinkRef") // "remote sender"

  //  // needed because serialization may want to serialize a ref, before we have finished materialization
  //  def bufferedRefFor[T](allocatedName: String, future: Future[SourceRefImpl[T]]): SourceRef[T] = {
  //    def mkProxyOnDemand(): ActorRef = {
  //      log.warning("HAVE TO CREATE PROXY!!!")
  //      val proxyName = allocatedName + "Proxy"
  //      system.actorOf(Props(new ProxyRefFor(future)), proxyName)
  //    }
  //    MaterializedSourceRef[T](future)
  //  }

  // TODO introduce a master with which all stages running the streams register themselves?

  def nextSourceRefStageName(): String =
    sourceRefStageNames.next()

  def nextSinkRefStageName(): String =
    sinkRefStageNames.next()

}

/** INTERNAL API */
@InternalApi
private[akka] final class ProxyRefFor[T](futureRef: Future[SourceRefImpl[T]]) extends Actor with Stash with ActorLogging {
  import context.dispatcher
  import akka.pattern.pipe

  override def preStart(): Unit = {
    futureRef.pipeTo(self)
  }

  override def receive: Receive = {
    case ref: SourceRefImpl[T] ⇒
      log.warning("REF:::: initial = " + ref.initialPartnerRef)
      context become initialized(ref)
      unstashAll()

    case msg ⇒
      log.warning("Stashing [{}], since target reference is still not initialized...", msg.getClass)
      stash()
  }

  def initialized(ref: SourceRefImpl[T]): Receive = {
    case any ⇒ ???
  }
}
