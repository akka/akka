/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl.streamref

import akka.NotUsed
import akka.annotation.InternalApi
import akka.serialization.SerializationExtension
import akka.serialization.Serializers
import akka.stream.ActorMaterializer
import akka.stream.Attributes
import akka.stream.FlowShape
import akka.stream.StreamRefMessages.Payload
import akka.stream.impl.fusing.GraphInterpreter
import akka.stream.impl.fusing.GraphStages.SimpleLinearGraphStage
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Framing
import akka.stream.stage.GraphStage
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.InHandler
import akka.stream.stage.OutHandler
import akka.util.ByteString

import scala.concurrent.Future

/*case class SerializedMessage(
  data:         ByteString,
  serializerId: Int,
  manifest:     String
)*/

@InternalApi
private[akka] object Chunking {
  // FIXME: somehow get from config
  val Parallelism = 16
  val MaxFrameSize = 1000000
  val MaxChunkSize = 16384

  def chunk[T]: Flow[T, ByteString, NotUsed] = {
    Flow[T]
      .mapAsync(Parallelism) { message ⇒
        val sys = GraphInterpreter.currentInterpreter.materializer.asInstanceOf[ActorMaterializer].system
        import sys.dispatcher
        Future {
          val serializer = SerializationExtension(sys).serializerFor(message.getClass)
          val ms = Serializers.manifestFor(serializer, message.asInstanceOf[AnyRef])

          import akka.protobuf.ByteString

          // copied from akka-remote MessageSerializer
          val builder = Payload.newBuilder
          builder.setEnclosedMessage(ByteString.copyFrom(serializer.toBinary(message.asInstanceOf[AnyRef])))
          builder.setSerializerId(serializer.identifier)
          if (ms.nonEmpty) builder.setMessageManifest(ByteString.copyFromUtf8(ms))

          akka.util.ByteString(builder.build.toByteArray)
        }
      }
      .via(Framing.simpleFramingProtocolEncoder(MaxFrameSize))
      .via(limitByteChunksStage(MaxChunkSize))
  }

  def unchunk[T]: Flow[ByteString, T, NotUsed] =
    Flow[ByteString].via(Framing.simpleFramingProtocolDecoder(MaxFrameSize)).mapAsync(Parallelism) { bytes ⇒
      val sys = GraphInterpreter.currentInterpreter.materializer.asInstanceOf[ActorMaterializer].system
      import sys.dispatcher
      Future {
        val messageProtocol = Payload.parseFrom(bytes.toArray)
        SerializationExtension(sys)
          .deserialize(
            messageProtocol.getEnclosedMessage.toByteArray,
            messageProtocol.getSerializerId,
            if (messageProtocol.hasMessageManifest) messageProtocol.getMessageManifest.toStringUtf8 else "")
          .get
          .asInstanceOf[T]
      }
    }

  // copied verbatim from akka-http, should move to a final place, somewhere in akka-stream
  private def limitByteChunksStage(maxBytesPerChunk: Int): GraphStage[FlowShape[ByteString, ByteString]] =
    new SimpleLinearGraphStage[ByteString] {
      override def initialAttributes = Attributes.name("limitByteChunksStage")

      override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

        var remaining = ByteString.empty

        def splitAndPush(elem: ByteString): Unit = {
          println(elem) // FIXME: elem isn't used?
          val toPush = remaining.take(maxBytesPerChunk)
          val toKeep = remaining.drop(maxBytesPerChunk)
          push(out, toPush)
          remaining = toKeep
        }
        setHandlers(in, out, WaitingForData)

        case object WaitingForData extends InHandler with OutHandler {
          override def onPush(): Unit = {
            val elem = grab(in)
            if (elem.size <= maxBytesPerChunk) push(out, elem)
            else {
              splitAndPush(elem)
              setHandlers(in, out, DeliveringData)
            }
          }
          override def onPull(): Unit = pull(in)
        }

        case object DeliveringData extends InHandler() with OutHandler {
          var finishing = false
          override def onPush(): Unit = throw new IllegalStateException("Not expecting data")
          override def onPull(): Unit = {
            splitAndPush(remaining)
            if (remaining.isEmpty) {
              if (finishing) completeStage() else setHandlers(in, out, WaitingForData)
            }
          }
          override def onUpstreamFinish(): Unit = if (remaining.isEmpty) completeStage() else finishing = true
        }

        override def toString = "limitByteChunksStage"
      }
    }
}
