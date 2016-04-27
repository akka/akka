/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery

import scala.concurrent.duration._
import java.net.InetSocketAddress
import java.nio.ByteOrder
import akka.NotUsed
import akka.actor.{ Address, ExtendedActorSystem }
import akka.remote.EndpointManager.Send
import akka.remote.{ InboundMessageDispatcher, MessageSerializer, RemoteActorRefProvider }
import akka.remote.transport.AkkaPduCodec
import akka.serialization.Serialization
import akka.stream.{ KillSwitches, SharedKillSwitch }
import akka.stream.scaladsl.{ Flow, Framing, Sink, Source, Tcp }
import akka.util.{ ByteString, ByteStringBuilder }
import scala.concurrent.Future
import akka.Done
import akka.stream.Materializer
import scala.concurrent.Await
import akka.event.LoggingAdapter
import akka.event.Logging
import io.aeron.driver.MediaDriver
import io.aeron.Aeron
import org.agrona.ErrorHandler
import io.aeron.AvailableImageHandler
import io.aeron.Image
import io.aeron.UnavailableImageHandler
import io.aeron.exceptions.ConductorServiceTimeoutException

/**
 * INTERNAL API
 */
// FIXME: Replace the codec with a custom made, hi-perf one
private[akka] class Transport(
  val localAddress: Address,
  val system: ExtendedActorSystem,
  val materializer: Materializer,
  val provider: RemoteActorRefProvider,
  val codec: AkkaPduCodec,
  val inboundDispatcher: InboundMessageDispatcher) {

  private val log: LoggingAdapter = Logging(system.eventStream, getClass.getName)

  private implicit val mat = materializer
  // TODO support port 0
  private val inboundChannel = s"aeron:udp?endpoint=${localAddress.host.get}:${localAddress.port.get}"

  private val aeron = {
    val ctx = new Aeron.Context

    ctx.availableImageHandler(new AvailableImageHandler {
      override def onAvailableImage(img: Image): Unit = {
        if (log.isDebugEnabled)
          log.debug(s"onAvailableImage from ${img.sourceIdentity} session ${img.sessionId}")
      }
    })
    ctx.unavailableImageHandler(new UnavailableImageHandler {
      override def onUnavailableImage(img: Image): Unit = {
        if (log.isDebugEnabled)
          log.debug(s"onUnavailableImage from ${img.sourceIdentity} session ${img.sessionId}")
        // FIXME we should call FragmentAssembler.freeSessionBuffer when image is unavailable
      }
    })
    ctx.errorHandler(new ErrorHandler {
      override def onError(cause: Throwable): Unit = {
        cause match {
          case e: ConductorServiceTimeoutException ⇒
            // Timeout between service calls
            log.error(cause, s"Aeron ServiceTimeoutException, ${cause.getMessage}")

          case _ ⇒
            log.error(cause, s"Aeron error, ${cause.getMessage}")
        }
      }
    })
    // TODO also support external media driver
    val driverContext = new MediaDriver.Context
    // FIXME settings from config
    driverContext.clientLivenessTimeoutNs(SECONDS.toNanos(10))
    driverContext.imageLivenessTimeoutNs(SECONDS.toNanos(10))
    driverContext.driverTimeoutMs(SECONDS.toNanos(10))
    val driver = MediaDriver.launchEmbedded(driverContext)

    ctx.aeronDirectoryName(driver.aeronDirectoryName)
    Aeron.connect(ctx)
  }

  private val taskRunner = new TaskRunner(system)

  def start(): Unit = {
    taskRunner.start()
    Source.fromGraph(new AeronSource(inboundChannel, aeron, taskRunner))
      .async // FIXME use dedicated dispatcher for AeronSource
      .map(ByteString.apply) // TODO we should use ByteString all the way
      .via(inboundFlow)
      .runWith(Sink.ignore)
  }

  def shutdown(): Future[Done] = {
    // FIXME stop the AeronSource first?
    taskRunner.stop()
    aeron.close()
    Future.successful(Done)
  }

  val killSwitch: SharedKillSwitch = KillSwitches.shared("transportKillSwitch")

  def outbound(remoteAddress: Address): Sink[Send, Any] = {
    val outboundChannel = s"aeron:udp?endpoint=${remoteAddress.host.get}:${remoteAddress.port.get}"
    Flow.fromGraph(killSwitch.flow[Send])
      .via(encoder)
      .map(_.toArray) // TODO we should use ByteString all the way
      .to(new AeronSink(outboundChannel, aeron, taskRunner))
  }

  // TODO: Try out parallelized serialization (mapAsync) for performance
  val encoder: Flow[Send, ByteString, NotUsed] = Flow[Send].map { sendEnvelope ⇒
    val pdu: ByteString = codec.constructMessage(
      sendEnvelope.recipient.localAddressToUse,
      sendEnvelope.recipient,
      Serialization.currentTransportInformation.withValue(Serialization.Information(localAddress, system)) {
        MessageSerializer.serialize(system, sendEnvelope.message.asInstanceOf[AnyRef])
      },
      sendEnvelope.senderOption,
      seqOption = None, // FIXME: Acknowledgements will be handled differently I just reused the old codec
      ackOption = None)

    // TODO: Drop unserializable messages
    // TODO: Drop oversized messages
    (new ByteStringBuilder).putInt(pdu.size)(ByteOrder.LITTLE_ENDIAN).result() ++ pdu
  }

  val decoder: Flow[ByteString, AkkaPduCodec.Message, NotUsed] =
    Framing.lengthField(4, maximumFrameLength = 256000)
      .map { frame ⇒
        // TODO: Drop unserializable messages
        val pdu = codec.decodeMessage(frame.drop(4), provider, localAddress)._2.get
        pdu
      }

  val messageDispatcher: Sink[AkkaPduCodec.Message, Any] = Sink.foreach[AkkaPduCodec.Message] { m ⇒
    inboundDispatcher.dispatch(m.recipient, m.recipientAddress, m.serializedMessage, m.senderOption)
  }

  val inboundFlow: Flow[ByteString, ByteString, NotUsed] = {
    Flow.fromSinkAndSource(
      decoder.to(messageDispatcher),
      Source.maybe[ByteString].via(killSwitch.flow))
  }

}
