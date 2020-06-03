/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.stream.typed

import akka.NotUsed
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior }
import akka.actor.typed.scaladsl.Behaviors

object ActorSourceSinkExample {

  implicit val system: ActorSystem[_] = ActorSystem(Behaviors.empty, "ActorSourceSinkExample")

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

    implicit val system: ActorSystem[_] = ???

    // #actor-source-with-backpressure
    import akka.actor.typed.ActorRef
    import akka.stream.CompletionStrategy
    import akka.stream.scaladsl.{ Sink, Source }
    import akka.stream.typed.scaladsl.ActorSource

    case object Ack

    trait Protocol
    case class Message(msg: String) extends Protocol
    case object Complete extends Protocol
    case class Fail(ex: Exception) extends Protocol

    def sendingActor: Behavior[Ack.type] =
      Behaviors.setup { context =>
        val source: Source[Protocol, ActorRef[Protocol]] =
          ActorSource.actorRefWithBackpressure[Protocol, Ack.type](
            // get demand signalled to this actor receiving Ack
            ackTo = context.self,
            ackMessage = Ack,
            // complete when we send Complete
            completionMatcher = {
              case Complete => CompletionStrategy.draining
            },
            failureMatcher = {
              case Fail(ex) => ex
            })

        val streamSource: ActorRef[Protocol] = source
          .collect {
            case Message(msg) => msg
          }
          .to(Sink.foreach(println))
          .run()

        streamSource ! Message("first")

        sender(streamSource, 0)
      }

    def sender(streamSource: ActorRef[Protocol], counter: Int): Behaviors.Receive[Ack.type] = Behaviors.receiveMessage {
      case Ack if counter < 5 =>
        streamSource ! Message(counter.toString)
        sender(streamSource, counter + 1)
      case _ =>
        streamSource ! Complete
        Behaviors.stopped
    }

    // Will print:
    // first
    // 0
    // 1
    // 2
    // 3
    // 4

    // #actor-source-with-backpressure
    if (sendingActor != null) println("yes!")
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

    // #actor-sink-ref-with-backpressure
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

    val sink: Sink[String, NotUsed] = ActorSink.actorRefWithBackpressure(
      ref = actor,
      onCompleteMessage = Complete,
      onFailureMessage = Fail.apply,
      messageAdapter = Message.apply,
      onInitMessage = Init.apply,
      ackMessage = Ack)

    Source.single("msg1").runWith(sink)
    // #actor-sink-ref-with-backpressure
  }
}
