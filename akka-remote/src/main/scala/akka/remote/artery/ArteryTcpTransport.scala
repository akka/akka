/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.security.SecureRandom
import javax.net.ssl._

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.Duration

import akka.Done
import akka.NotUsed
import akka.actor.ExtendedActorSystem
import akka.dispatch.ExecutionContexts
import akka.event.Logging
import akka.remote.RemoteActorRefProvider
import akka.remote.RemoteTransportException
import akka.remote.artery.Decoder.InboundCompressionAccess
import akka.remote.artery.compress._
import akka.stream.Attributes
import akka.stream.Attributes.LogLevels
import akka.stream.Client
import akka.stream.IgnoreComplete
import akka.stream.KillSwitches
import akka.stream.Server
import akka.stream.SharedKillSwitch
import akka.stream.SinkShape
import akka.stream.TLSProtocol._
import akka.stream.TLSRole
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Framing
import akka.stream.scaladsl.GraphDSL
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.MergeHub
import akka.stream.scaladsl.Partition
import akka.stream.scaladsl.RestartFlow
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.TLS
import akka.stream.scaladsl.Tcp
import akka.stream.scaladsl.Tcp.ServerBinding
import akka.util.ByteString

/**
 * INTERNAL API
 */
private[remote] class ArteryTcpTransport(_system: ExtendedActorSystem, _provider: RemoteActorRefProvider,
                                         tlsEnabled: Boolean)
  extends ArteryTransport(_system, _provider) {
  import ArteryTransport.InboundStreamMatValues
  import FlightRecorderEvents._

  @volatile private var inboundConnectionsKillSwitch: SharedKillSwitch = KillSwitches.shared("inboundConnectionsKillSwitch")
  private var serverBinding: Option[Future[ServerBinding]] = None

  override protected def startTransport(): Unit = {
    // FIXME new TCP events
    topLevelFREvents.loFreq(Transport_AeronStarted, NoMetaData)
  }

  override protected def outboundTransportSink(
    outboundContext: OutboundContext,
    streamId:        Int,
    bufferPool:      EnvelopeBufferPool): Sink[EnvelopeBuffer, Future[Done]] = {
    implicit val sys = system

    val host = outboundContext.remoteAddress.host.get
    val port = outboundContext.remoteAddress.port.get

    def tcp = Tcp()
      .outgoingConnection(
        remoteAddress = InetSocketAddress.createUnresolved(host, port),
        halfClose = true, // issue https://github.com/akka/akka/issues/24392 if set to false
        connectTimeout = Duration.Inf // FIXME should this be set, default is Inf?
      )

    def connectionFlow: Flow[ByteString, ByteString, Future[Tcp.OutgoingConnection]] =
      if (tlsEnabled) {
        Flow[ByteString]
          .map(bytes ⇒ SendBytes(bytes))
          .viaMat(tls(role = Client).joinMat(tcp)(Keep.right))(Keep.right)
          .collect {
            case SessionBytes(_, bytes) ⇒ bytes
          }
      } else
        tcp

    def connectionFlowWithRestart: Flow[ByteString, ByteString, NotUsed] = {
      val flowFactory = () ⇒ connectionFlow.mapMaterializedValue(_ ⇒ NotUsed)
        .log(name = s"outbound connection to [${outboundContext.remoteAddress}]")
        .addAttributes(Attributes.logLevels(onElement = LogLevels.Off))

      // FIXME config of backoff
      import scala.concurrent.duration._
      if (streamId == controlStreamId) {
        // restart of inner connection part important in control flow, since system messages
        // are buffered and resent from the outer SystemMessageDelivery stage.
        // FIXME The mat value Future is currently never completed, because RestartFlow will retry forever, should it give up?
        //       related to give-up-system-message-after ?
        RestartFlow.withBackoff[ByteString, ByteString](1.second, 5.seconds, 0.1)(flowFactory)
      } else {
        // Best effort retry a few times
        RestartFlow.withBackoff[ByteString, ByteString](1.second, 5.seconds, 0.1, maxRestarts = 3)(flowFactory)
      }
    }

    Flow[EnvelopeBuffer]
      .map { env ⇒
        val bytes = ByteString(env.byteBuffer)
        bufferPool.release(env)
        bytes
      }
      .recoverWithRetries(1, { case ArteryTransport.ShutdownSignal ⇒ Source.empty })
      .via(connectionFlowWithRestart)
      .map(_ ⇒ throw new IllegalStateException(s"Unexpected incoming bytes in outbound connection to [${outboundContext.remoteAddress}]"))
      .toMat(Sink.ignore)(Keep.right)

    // FIXME The mat value Future is currently never completed, because RestartFlow will retry forever, should it give up?
    //       related to give-up-system-message-after ?

  }

  override protected def runInboundStreams(): Unit = {
    implicit val mat = materializer
    implicit val sys = system

    val (controlStream, controlStreamCompleted) = runInboundControlStream()
    val (ordinaryMessagesStream, ordinaryMessagesStreamCompleted) = runInboundOrdinaryMessagesStream()
    val (largeMessagesStream, largeMessagesStreamCompleted) = {
      if (largeMessageChannelEnabled)
        runInboundLargeMessagesStream()
      else
        (
          Flow[EnvelopeBuffer]
          .map(_ ⇒ log.warning("Dropping large message, missing large-message-destinations configuration."))
          .to(Sink.ignore),
          Promise[Done]().future) // never completed, not enabled
    }

    val inboundStream: Sink[EnvelopeBuffer, NotUsed] =
      Sink.fromGraph(GraphDSL.create() { implicit b ⇒
        import GraphDSL.Implicits._
        val partition = b.add(Partition[EnvelopeBuffer](3, env ⇒ {
          env.byteBuffer.get(EnvelopeBuffer.StreamIdOffset).toInt match {
            case `ordinaryStreamId` ⇒ 1
            case `controlStreamId`  ⇒ 0
            case `largeStreamId`    ⇒ 2
          }
        }))
        partition.out(0) ~> controlStream
        partition.out(1) ~> ordinaryMessagesStream
        partition.out(2) ~> largeMessagesStream
        SinkShape(partition.in)
      })

    val maxFrameSize =
      if (largeMessageChannelEnabled) settings.Advanced.MaximumLargeFrameSize
      else settings.Advanced.MaximumFrameSize

    // FIXME error handling in case something fails in this part

    val inboundConnectionFlow = Flow[ByteString]
      .via(inboundConnectionsKillSwitch.flow)
      .via(Framing.lengthField(fieldLength = 4, fieldOffset = EnvelopeBuffer.FrameLengthOffset,
        maxFrameSize, byteOrder = ByteOrder.LITTLE_ENDIAN))
      .map { frame ⇒
        val buffer = ByteBuffer.wrap(frame.toArray)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        new EnvelopeBuffer(buffer)
      }
      .alsoTo(inboundStream)
      .filter(_ ⇒ false) // don't send back anything in this TCP socket
      .map(_ ⇒ ByteString.empty) // make it a Flow[ByteString] again

    val host = localAddress.address.host.get
    val port = localAddress.address.port.get

    serverBinding =
      Some(Tcp().bind(
        interface = localAddress.address.host.get,
        port = localAddress.address.port.get,
        halfClose = false)
        .to(Sink.foreach { connection ⇒
          if (tlsEnabled) {
            val rhs: Flow[SslTlsInbound, SslTlsOutbound, Any] =
              Flow[SslTlsInbound]
                .collect {
                  case SessionBytes(_, bytes) ⇒ bytes
                }
                .via(inboundConnectionFlow)
                .map(SendBytes.apply)

            connection.handleWith(tls(role = Server).reversed join rhs)
          } else
            connection.handleWith(inboundConnectionFlow)
        })
        .run()
        .recoverWith {
          case e ⇒ Future.failed(new RemoteTransportException(
            s"Failed to bind TCP to [${localAddress.address.host.get}:${localAddress.address.port.get}] due to: " +
              e.getMessage, e))
        }(ExecutionContexts.sameThreadExecutionContext)
      )

    Await.result(serverBinding.get, settings.Bind.BindTimeout)

    implicit val ec = materializer.executionContext
    val completed = Future.firstCompletedOf(
      List(controlStreamCompleted, ordinaryMessagesStreamCompleted, largeMessagesStreamCompleted))
    val restart = () ⇒ {
      inboundConnectionsKillSwitch.shutdown()
      inboundConnectionsKillSwitch = KillSwitches.shared("inboundConnectionsKillSwitch")
      unbind().onComplete { _ ⇒
        runInboundStreams()
      }
    }
    attachInboundStreamRestart("Inbound streams", completed, restart)
  }

  private def runInboundControlStream(): (Sink[EnvelopeBuffer, NotUsed], Future[Done]) = {
    if (isShutdown) throw ArteryTransport.ShuttingDown

    val (hub, ctrl, completed) =
      MergeHub.source[EnvelopeBuffer].addAttributes(Attributes.logLevels(onFailure = LogLevels.Off))
        .via(inboundFlow(settings, NoInboundCompressions))
        .toMat(inboundControlSink)({ case (a, (c, d)) ⇒ (a, c, d) })
        .run()(controlMaterializer)
    attachControlMessageObserver(ctrl)
    implicit val ec = materializer.executionContext
    updateStreamMatValues(controlStreamId, completed)

    (hub, completed)
  }

  private def runInboundOrdinaryMessagesStream(): (Sink[EnvelopeBuffer, NotUsed], Future[Done]) = {
    if (isShutdown) throw ArteryTransport.ShuttingDown

    val (inboundHub: Sink[EnvelopeBuffer, NotUsed], inboundCompressionAccess, completed) =
      if (inboundLanes == 1) {
        MergeHub.source[EnvelopeBuffer].addAttributes(Attributes.logLevels(onFailure = LogLevels.Off))
          .viaMat(inboundFlow(settings, _inboundCompressions))(Keep.both)
          .toMat(inboundSink(envelopeBufferPool))({ case ((a, b), c) ⇒ (a, b, c) })
          .run()(materializer)

      } else {
        // TODO perhaps a few more things can be extracted and DRY with AeronUpdTransport.runInboundOrdinaryMessagesStream
        val hubKillSwitch = KillSwitches.shared("hubKillSwitch")

        val source: Source[InboundEnvelope, (Sink[EnvelopeBuffer, NotUsed], InboundCompressionAccess)] =
          MergeHub.source[EnvelopeBuffer].addAttributes(Attributes.logLevels(onFailure = LogLevels.Off))
            .via(hubKillSwitch.flow)
            .viaMat(inboundFlow(settings, _inboundCompressions))(Keep.both)
            .via(Flow.fromGraph(new DuplicateHandshakeReq(inboundLanes, this, system, envelopeBufferPool)))

        val (inboundHub, compressionAccess, hub) =
          source
            .toMat(Sink.fromGraph(new FixedSizePartitionHub[InboundEnvelope](inboundLanePartitioner, inboundLanes,
              settings.Advanced.InboundHubBufferSize)))({ case ((a, b), c) ⇒ (a, b, c) })
            .run()(materializer)

        val lane = inboundSink(envelopeBufferPool)
        val completedValues: Vector[Future[Done]] =
          (0 until inboundLanes).map { _ ⇒
            hub.toMat(lane)(Keep.right).run()(materializer)
          }(collection.breakOut)

        import system.dispatcher

        // tear down the upstream hub part if downstream lane fails
        // lanes are not completed with success by themselves so we don't have to care about onSuccess
        Future.firstCompletedOf(completedValues).failed.foreach { reason ⇒ hubKillSwitch.abort(reason) }
        val allCompleted = Future.sequence(completedValues).map(_ ⇒ Done)

        (inboundHub, compressionAccess, allCompleted)
      }

    setInboundCompressionAccess(inboundCompressionAccess)

    updateStreamMatValues(ordinaryStreamId, completed)

    (inboundHub, completed)
  }

  private def runInboundLargeMessagesStream(): (Sink[EnvelopeBuffer, NotUsed], Future[Done]) = {
    if (isShutdown) throw ArteryTransport.ShuttingDown

    val (hub, completed) =
      MergeHub.source[EnvelopeBuffer].addAttributes(Attributes.logLevels(onFailure = LogLevels.Off))
        .via(inboundLargeFlow(settings))
        .toMat(inboundSink(largeEnvelopeBufferPool))(Keep.both)
        .run()(materializer)

    updateStreamMatValues(largeStreamId, completed)

    (hub, completed)
  }

  private def updateStreamMatValues(streamId: Int, completed: Future[Done]): Unit = {
    implicit val ec = materializer.executionContext
    updateStreamMatValues(controlStreamId, InboundStreamMatValues(
      None,
      completed.recover { case _ ⇒ Done }))
  }

  override protected def shutdownTransport(): Future[Done] = {
    implicit val ec = materializer.executionContext
    inboundConnectionsKillSwitch.shutdown()
    unbind().map { _ ⇒
      topLevelFREvents.loFreq(Transport_Stopped, NoMetaData)
      Done
    }
  }

  private def unbind(): Future[Done] = {
    implicit val ec = materializer.executionContext
    serverBinding match {
      case Some(binding) ⇒
        for {
          b ← binding
          _ ← b.unbind()
        } yield {
          Done
        }
      case None ⇒
        Future.successful(Done)
    }
  }

  // FIXME proper SslConfig

  def initWithTrust(trustPath: String) = {
    val password = "changeme"

    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType)
    keyStore.load(getClass.getResourceAsStream("/keystore"), password.toCharArray)

    val trustStore = KeyStore.getInstance(KeyStore.getDefaultType)
    trustStore.load(getClass.getResourceAsStream(trustPath), password.toCharArray)

    val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    keyManagerFactory.init(keyStore, password.toCharArray)

    val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    trustManagerFactory.init(trustStore)

    val context = SSLContext.getInstance("TLS")
    context.init(keyManagerFactory.getKeyManagers, trustManagerFactory.getTrustManagers, new SecureRandom)
    context
  }

  def initSslContext(): SSLContext = initWithTrust("/truststore")

  lazy val sslContext = initSslContext()
  lazy val cipherSuites = NegotiateNewSession.withCipherSuites("TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA", "TLS_RSA_WITH_AES_128_CBC_SHA")

  def tls(role: TLSRole) = TLS(sslContext, None, cipherSuites, role, IgnoreComplete)

}
