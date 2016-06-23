/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

import akka.remote.artery.compress._
import akka.stream.impl.ConstantFun
import org.openjdk.jmh.annotations.Scope

import scala.concurrent.Await
import scala.concurrent.duration._
import akka.NotUsed
import akka.actor._
import akka.remote.AddressUidExtension
import akka.remote.EndpointManager.Send
import akka.remote.RARP
import akka.remote.RemoteActorRef
import akka.remote.UniqueAddress
import akka.stream.ActorMaterializer
import akka.stream.ActorMaterializerSettings
import akka.stream.scaladsl._
import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations._
import akka.util.OptionVal
import akka.actor.Address

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@BenchmarkMode(Array(Mode.Throughput))
@Fork(2)
@Warmup(iterations = 4)
@Measurement(iterations = 5)
class CodecBenchmark {

  val config = ConfigFactory.parseString(
    """
    akka {
       loglevel = WARNING
       actor.provider = remote
       remote.artery.enabled = on
       remote.artery.hostname = localhost
       remote.artery.port = 0
     }
    """
  )

  implicit val system = ActorSystem("CodecBenchmark", config)
  val systemB = ActorSystem("systemB", system.settings.config)

  val envelopePool = new EnvelopeBufferPool(ArteryTransport.MaximumFrameSize, ArteryTransport.MaximumPooledBuffers)
  val inboundEnvelopePool = new ObjectPool[InboundEnvelope](
    16,
    create = () ⇒ new ReusableInboundEnvelope, clear = inEnvelope ⇒ inEnvelope.asInstanceOf[ReusableInboundEnvelope].clear()
  )

  val compressionOut = NoOutboundCompression
  val headerIn = HeaderBuilder.in(NoopInboundCompression)
  val envelopeTemplateBuffer = ByteBuffer.allocate(ArteryTransport.MaximumFrameSize).order(ByteOrder.LITTLE_ENDIAN)

  val uniqueLocalAddress = UniqueAddress(
    system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress,
    AddressUidExtension(system).addressUid
  )
  val payload = Array.ofDim[Byte](1000)

  private val inboundContext: InboundContext = new InboundContext {
    override def localAddress: UniqueAddress = uniqueLocalAddress
    override def association(uid: Long): OptionVal[OutboundContext] = OptionVal.None
    // the following methods are not used by in this test
    override def sendControl(to: Address, message: ControlMessage): Unit = ???
    override def association(remoteAddress: Address): OutboundContext = ???
    override def completeHandshake(peer: UniqueAddress): Unit = ???
  }

  private var materializer: ActorMaterializer = _
  private var remoteRefB: RemoteActorRef = _
  private var resolvedRef: InternalActorRef = _
  private var senderStringA: String = _
  private var recipientStringB: String = _

  @Setup
  def setup(): Unit = {
    val settings = ActorMaterializerSettings(system)
    materializer = ActorMaterializer(settings)

    val actorOnSystemA = system.actorOf(Props.empty, "a")
    senderStringA = actorOnSystemA.path.toSerializationFormatWithAddress(uniqueLocalAddress.address)

    val actorOnSystemB = systemB.actorOf(Props.empty, "b")
    val addressB = systemB.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
    val rootB = RootActorPath(addressB)
    remoteRefB =
      Await.result(system.actorSelection(rootB / "user" / "b").resolveOne(5.seconds), 5.seconds)
        .asInstanceOf[RemoteActorRef]
    resolvedRef = actorOnSystemA.asInstanceOf[InternalActorRef]
    recipientStringB = remoteRefB.path.toSerializationFormatWithAddress(addressB)

    val envelope = new EnvelopeBuffer(envelopeTemplateBuffer)
    headerIn.version = 1
    headerIn.uid = 42
    headerIn.serializer = 4
    headerIn.senderActorRef = actorOnSystemA
    headerIn.recipientActorRef = remoteRefB
    headerIn.manifest = ""
    envelope.writeHeader(headerIn)
    envelope.byteBuffer.put(payload)
    envelope.byteBuffer.flip()
  }

  @TearDown
  def shutdown(): Unit = {
    Await.result(system.terminate(), 5.seconds)
    Await.result(systemB.terminate(), 5.seconds)
  }

  @Benchmark
  @OperationsPerInvocation(100000)
  def reference(): Unit = {
    val latch = new CountDownLatch(1)
    val N = 100000

    Source.fromGraph(new BenchTestSourceSameElement(N, "elem"))
      .runWith(new LatchSink(N, latch))(materializer)

    if (!latch.await(30, TimeUnit.SECONDS))
      throw new RuntimeException("Latch didn't complete in time")
  }

  @Benchmark
  @OperationsPerInvocation(100000)
  def encode(): Unit = {
    val latch = new CountDownLatch(1)
    val N = 100000

    val encoder: Flow[Send, EnvelopeBuffer, NotUsed] =
      Flow.fromGraph(new Encoder(uniqueLocalAddress, system, compressionOut, envelopePool))

    Source.fromGraph(new BenchTestSourceSameElement(N, "elem"))
      .map(_ ⇒ Send(payload, OptionVal.None, remoteRefB, None))
      .via(encoder)
      .map(envelope => envelopePool.release(envelope))
      .runWith(new LatchSink(N, latch))(materializer)

    if (!latch.await(30, TimeUnit.SECONDS))
      throw new RuntimeException("Latch didn't complete in time")
  }

  @Benchmark
  @OperationsPerInvocation(100000)
  def decode(): Unit = {
    val latch = new CountDownLatch(1)
    val N = 100000

    val localRecipient = resolvedRef.path.toSerializationFormatWithAddress(uniqueLocalAddress.address)
    val provider = RARP(system).provider
    val resolveActorRefWithLocalAddress: String ⇒ InternalActorRef = {
      recipient ⇒
        // juggling with the refs, since we don't run the real thing
        val resolved = provider.resolveActorRefWithLocalAddress(localRecipient, uniqueLocalAddress.address)
        resolved
    }

    val decoder: Flow[EnvelopeBuffer, InboundEnvelope, NotUsed] =
      Flow.fromGraph(new Decoder(inboundContext, system.asInstanceOf[ExtendedActorSystem],
        resolveActorRefWithLocalAddress, NoopInboundCompression, envelopePool, inboundEnvelopePool))

    Source.fromGraph(new BenchTestSourceSameElement(N, "elem"))
      .map { _ =>
        val envelope = envelopePool.acquire()
        envelopeTemplateBuffer.rewind()
        envelope.byteBuffer.put(envelopeTemplateBuffer)
        envelope.byteBuffer.flip()
        envelope
      }
      .via(decoder)
      .map { env =>
        inboundEnvelopePool.release(env)
        ()
      }
      .runWith(new LatchSink(N, latch))(materializer)

    if (!latch.await(30, TimeUnit.SECONDS))
      throw new RuntimeException("Latch didn't complete in time")
  }

  @Benchmark
  @OperationsPerInvocation(100000)
  def encode_decode(): Unit = {
    val latch = new CountDownLatch(1)
    val N = 100000

    val encoder: Flow[Send, EnvelopeBuffer, NotUsed] =
      Flow.fromGraph(new Encoder(uniqueLocalAddress, system, compressionOut, envelopePool))

    val localRecipient = resolvedRef.path.toSerializationFormatWithAddress(uniqueLocalAddress.address)
    val provider = RARP(system).provider
    val resolveActorRefWithLocalAddress: String ⇒ InternalActorRef = {
      recipient ⇒
        // juggling with the refs, since we don't run the real thing
        val resolved = provider.resolveActorRefWithLocalAddress(localRecipient, uniqueLocalAddress.address)
        resolved
    }

    val decoder: Flow[EnvelopeBuffer, InboundEnvelope, NotUsed] =
      Flow.fromGraph(new Decoder(inboundContext, system.asInstanceOf[ExtendedActorSystem],
        resolveActorRefWithLocalAddress, NoopInboundCompression, envelopePool, inboundEnvelopePool))

    Source.fromGraph(new BenchTestSourceSameElement(N, "elem"))
      .map(_ ⇒ Send(payload, OptionVal.None, remoteRefB, None))
      .via(encoder)
      .via(decoder)
      .map { env =>
        inboundEnvelopePool.release(env)
        ()
      }
      .runWith(new LatchSink(N, latch))(materializer)

    if (!latch.await(30, TimeUnit.SECONDS))
      throw new RuntimeException("Latch didn't complete in time")
  }

}
