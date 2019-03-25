/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.stream.typed

import akka.NotUsed
import akka.actor.typed.{ ActorRef, ActorSystem }
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.typed.scaladsl.ActorMaterializer

object ActorSourceSinkExample {

  val system: ActorSystem[_] = ActorSystem(Behaviors.empty, "ActorSourceSinkExample")

  implicit val mat: ActorMaterializer = ActorMaterializer()(system)

  {
    // #actor-source-ref
    import akka.actor.typed.ActorRef
    import akka.stream.OverflowStrategy
    import akka.stream.scaladsl.{ Sink, Source }
    import akka.stream.typed.scaladsl.ActorSource

    trait Protocol
    case class Message(msg: String) extends Protocol
    case object Complete extends Protocol
    case class Fail(ex: Exception) extends Protocol

    val source: Source[Protocol, ActorRef[Protocol]] = ActorSource.actorRef[Protocol](completionMatcher = {
      case Complete =>
    }, failureMatcher = {
      case Fail(ex) => ex
    }, bufferSize = 8, overflowStrategy = OverflowStrategy.fail)

    val ref = source
      .collect {
        case Message(msg) => msg
      }
      .to(Sink.foreach(println))
      .run()

    ref ! Message("msg1")
    // ref ! "msg2" Does not compile
    // #actor-source-ref
  }

  {
    def targetActor(): ActorRef[Protocol] = ???

    // #actor-sink-ref
    import akka.actor.typed.ActorRef
    import akka.stream.scaladsl.{ Sink, Source }
    import akka.stream.typed.scaladsl.ActorSink

    trait Protocol
    case class Message(msg: String) extends Protocol
    case object Complete extends Protocol
    case class Fail(ex: Throwable) extends Protocol

    val actor: ActorRef[Protocol] = targetActor()

    val sink: Sink[Protocol, NotUsed] =
      ActorSink.actorRef[Protocol](ref = actor, onCompleteMessage = Complete, onFailureMessage = Fail.apply)

    Source.single(Message("msg1")).runWith(sink)
    // #actor-sink-ref

  }

  {

    def targetActor(): ActorRef[Protocol] = ???

    // #actor-sink-ref-with-ack
    import akka.actor.typed.ActorRef
    import akka.stream.scaladsl.{ Sink, Source }
    import akka.stream.typed.scaladsl.ActorSink

    trait Ack
    object Ack extends Ack

    trait Protocol
    case class Init(ackTo: ActorRef[Ack]) extends Protocol
    case class Message(ackTo: ActorRef[Ack], msg: String) extends Protocol
    case object Complete extends Protocol
    case class Fail(ex: Throwable) extends Protocol

    val actor: ActorRef[Protocol] = targetActor()

    val sink: Sink[String, NotUsed] = ActorSink.actorRefWithAck(
      ref = actor,
      onCompleteMessage = Complete,
      onFailureMessage = Fail.apply,
      messageAdapter = Message.apply,
      onInitMessage = Init.apply,
      ackMessage = Ack)

    Source.single("msg1").runWith(sink)
    // #actor-sink-ref-with-ack
  }
}
