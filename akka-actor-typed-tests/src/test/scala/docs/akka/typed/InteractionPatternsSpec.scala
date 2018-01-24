/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.akka.typed

import java.net.URI

import akka.actor.typed.{ ActorRef, ActorSystem, Behavior, TypedAkkaSpecWithShutdown }
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors, TimerScheduler }
import akka.testkit.typed.TestKit
import akka.testkit.typed.scaladsl.TestProbe
import akka.util.Timeout

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

class InteractionPatternsSpec extends TestKit with TypedAkkaSpecWithShutdown {

  "The interaction patterns docs" must {

    "contain a sample for fire and forget" in {
      // #fire-and-forget-definition

      sealed trait PrinterProtocol
      case object DisableOutput extends PrinterProtocol
      case object EnableOutput extends PrinterProtocol
      case class PrintMe(message: String) extends PrinterProtocol

      // two state behavior
      // in this state messages are printed
      def enabledPrinterBehavior: Behavior[PrinterProtocol] = Behaviors.immutable {
        // switch to the non printing-behavior
        case (_, DisableOutput) ⇒ disabledPrinterBehavior
        case (_, EnableOutput)  ⇒ Behaviors.ignore
        case (_, PrintMe(message)) ⇒
          println(message)
          Behaviors.same
      }

      // in this state messages are swallowed
      def disabledPrinterBehavior: Behavior[PrinterProtocol] = Behaviors.immutable {
        // switch back to the printing behavior
        case (_, DisableOutput) ⇒ enabledPrinterBehavior

        // ignore any other message
        case (_, _)             ⇒ Behaviors.ignore
      }
      // #fire-and-forget-definition

      // #fire-and-forget-doit
      val system = ActorSystem(enabledPrinterBehavior, "fire-and-forget-sample")

      // note how the system is also the top level actor ref
      val printer: ActorRef[PrinterProtocol] = system

      // these are all fire and forget
      printer ! PrintMe("printed")
      printer ! DisableOutput
      printer ! PrintMe("not printed")
      printer ! EnableOutput
      // #fire-and-forget-doit

      system.terminate().futureValue
    }

    // #request-response-protocol
    trait Protocol
    case class Request(query: String, respondTo: ActorRef[Response]) extends Protocol
    case class Response(result: String)
    // #request-response-protocol

    def compileOnlyRequestResponse(): Unit = {
      // #request-response-respond
      Behaviors.immutable[Protocol] { (ctx, msg) ⇒
        msg match {
          case Request(query, respondTo) ⇒
            // ... process query ...
            respondTo ! Response("Here's your response!")
            Behaviors.same
        }
      }
      // #request-response-respond

      val otherActor: ActorRef[Protocol] = ???
      val ctx: ActorContext[Response] = ???
      // #request-response-send
      otherActor ! Request("give me cookies", ctx.self)
      // #request-response-send
    }

    "contain a sample for adapted response" in {
      // #adapted-response

      object Backend {
        sealed trait Request
        final case class StartTranslationJob(taskId: Int, site: URI, replyTo: ActorRef[Response]) extends Request

        sealed trait Response
        final case class JobStarted(taskId: Int) extends Response
        final case class JobProgress(taskId: Int, progress: Double) extends Response
        final case class JobCompleted(taskId: Int, result: URI) extends Response
      }

      object Frontend {

        sealed trait Command
        final case class Translate(site: URI, replyTo: ActorRef[URI]) extends Command
        private final case class WrappedBackendResponse(response: Backend.Response) extends Command

        def translator(backend: ActorRef[Backend.Request]): Behavior[Command] =
          Behaviors.deferred[Command] { ctx ⇒
            val backendResponseMapper: ActorRef[Backend.Response] =
              ctx.messageAdapter(rsp ⇒ WrappedBackendResponse(rsp))

            def active(
              inProgress: Map[Int, ActorRef[URI]],
              count:      Int): Behavior[Command] = {
              Behaviors.immutable[Command] { (_, msg) ⇒
                msg match {
                  case Translate(site, replyTo) ⇒
                    val taskId = count + 1
                    backend ! Backend.StartTranslationJob(taskId, site, backendResponseMapper)
                    active(inProgress.updated(taskId, replyTo), taskId)

                  case wrapped: WrappedBackendResponse ⇒ wrapped.response match {
                    case Backend.JobStarted(taskId) ⇒
                      println(s"Started $taskId")
                      Behaviors.same
                    case Backend.JobProgress(taskId, progress) ⇒
                      println(s"Progress $taskId: $progress")
                      Behaviors.same
                    case Backend.JobCompleted(taskId, result) ⇒
                      println(s"Completed $taskId: $result")
                      inProgress(taskId) ! result
                      active(inProgress - taskId, count)
                  }
                }
              }
            }

            active(inProgress = Map.empty, count = 0)
          }
      }
      // #adapted-response

      val backend = spawn(Behaviors.immutable[Backend.Request] { (_, msg) ⇒
        msg match {
          case Backend.StartTranslationJob(taskId, site, replyTo) ⇒
            replyTo ! Backend.JobStarted(taskId)
            replyTo ! Backend.JobProgress(taskId, 0.25)
            replyTo ! Backend.JobProgress(taskId, 0.50)
            replyTo ! Backend.JobProgress(taskId, 0.75)
            replyTo ! Backend.JobCompleted(taskId, new URI("https://akka.io/docs/sv/"))
            Behaviors.same
        }
      })

      val frontend = spawn(Frontend.translator(backend))
      val probe = TestProbe[URI]()
      frontend ! Frontend.Translate(new URI("https://akka.io/docs/"), probe.ref)
      probe.expectMessage(new URI("https://akka.io/docs/sv/"))
    }

  }

  "contain a sample for scheduling messages to self" in {

    //#timer
    case object TimerKey

    trait Msg
    case class ExcitingMessage(msg: String) extends Msg
    final case class Batch(messages: Vector[Msg])
    case object Timeout extends Msg

    def behavior(target: ActorRef[Batch], after: FiniteDuration, maxSize: Int): Behavior[Msg] = {
      Behaviors.withTimers(timers ⇒ idle(timers, target, after, maxSize))
    }

    def idle(timers: TimerScheduler[Msg], target: ActorRef[Batch],
             after: FiniteDuration, maxSize: Int): Behavior[Msg] = {
      Behaviors.immutable[Msg] { (ctx, msg) ⇒
        timers.startSingleTimer(TimerKey, Timeout, after)
        active(Vector(msg), timers, target, after, maxSize)
      }
    }

    def active(buffer: Vector[Msg], timers: TimerScheduler[Msg],
               target: ActorRef[Batch], after: FiniteDuration, maxSize: Int): Behavior[Msg] = {
      Behaviors.immutable[Msg] { (_, msg) ⇒
        msg match {
          case Timeout ⇒
            target ! Batch(buffer)
            idle(timers, target, after, maxSize)
          case m ⇒
            val newBuffer = buffer :+ m
            if (newBuffer.size == maxSize) {
              timers.cancel(TimerKey)
              target ! Batch(newBuffer)
              idle(timers, target, after, maxSize)
            } else
              active(newBuffer, timers, target, after, maxSize)
        }
      }
    }
    //#timer

    val probe: TestProbe[Batch] = TestProbe[Batch]()
    val bufferer: ActorRef[Msg] = spawn(behavior(probe.ref, 1.second, 10))
    bufferer ! ExcitingMessage("one")
    bufferer ! ExcitingMessage("two")
    probe.expectNoMessage(1.millisecond)
    probe.expectMessage(2.seconds, Batch(Vector[Msg](ExcitingMessage("one"), ExcitingMessage("two"))))
  }

  "contain a sample for ask" in {
    // #actor-ask
    trait HalProtocol
    case class OpenThePodBayDoorsPlease(respondTo: ActorRef[HalResponse]) extends HalProtocol
    case class HalResponse(message: String)

    val halBehavior = Behaviors.immutable[HalProtocol] { (ctx, msg) ⇒
      msg match {
        case OpenThePodBayDoorsPlease(respondTo) ⇒
          respondTo ! HalResponse("I'm sorry Dave, I cannot do that!")
          Behaviors.same
      }
    }

    trait DaveProtocol
    // this is a part of the protocol that is internal to the actor itself
    case class AdaptedResponse(message: String) extends DaveProtocol

    def daveBehavior(hal: ActorRef[HalProtocol]) = Behaviors.deferred[DaveProtocol] { ctx ⇒

      // asking someone requires a timeout, if the timeout hits without response
      // the ask is failed with a TimeoutException
      implicit val timeout: Timeout = 3.seconds

      // Note: The second parameter list takes a function `ActorRef[T] => Message`,
      // as OpenThePodBayDoorsPlease is a case class it has a factory apply method
      // that is what we are passing as the second parameter here it could also be written
      // as `ref => OpenThePodBayDoorsPlease(ref)`
      ctx.ask(hal)(OpenThePodBayDoorsPlease) {
        case Success(HalResponse(message)) ⇒ AdaptedResponse(message)
        case Failure(ex)                   ⇒ AdaptedResponse("Request failed")
      }

      // we can also tie in request context into an interaction, it is safe to look at
      // actor internal state from the transformation function, but remember that it may have
      // changed at the time the response arrives and the transformation is done, best is to
      // use immutable state we have closed over like here.
      val requestId = 1
      ctx.ask(hal)(OpenThePodBayDoorsPlease) {
        case Success(HalResponse(message)) ⇒ AdaptedResponse(s"$requestId: message")
        case Failure(ex)                   ⇒ AdaptedResponse(s"$requestId: Request failed")
      }

      Behaviors.immutable { (ctx, msg) ⇒
        msg match {
          // the adapted message ends up being processed like any other
          // message sent to the actor
          case AdaptedResponse(msg) ⇒
            println(s"Got response from hal: $msg")
            Behaviors.same
        }
      }
    }
    // #actor-ask
  }

  "contain a sample for ask from outside the actor system" in {
    // #standalone-ask
    trait CookieProtocol {}
    case class GiveMeCookies private[typed] (val cookies: ActorRef[Cookies]) extends CookieProtocol
    case class Cookies(count: Int)

    def askAndPrint(system: ActorSystem[AnyRef], cookieActorRef: ActorRef[CookieProtocol]): Unit = {
      import akka.actor.typed.scaladsl.AskPattern._

      // asking someone requires a timeout, if the timeout hits without response
      // the ask is failed with a TimeoutException
      implicit val timeout: Timeout = 3.seconds
      import system.executionContext

      val result: Future[Cookies] = cookieActorRef ? GiveMeCookies

      result.onComplete {
        case Success(cookies) ⇒ println("Yay, cookies!")
        case Failure(ex)      ⇒ println("Boo! didn't get cookies in time.")
      }
    }
    // #standalone-ask
  }
}
