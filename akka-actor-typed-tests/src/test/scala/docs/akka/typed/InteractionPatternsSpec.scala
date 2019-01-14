/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.typed

import java.net.URI

import akka.NotUsed
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior }
import akka.actor.typed.scaladsl.{ Behaviors, TimerScheduler }
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.util.Timeout

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{ Failure, Success }
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.WordSpecLike

class InteractionPatternsSpec extends ScalaTestWithActorTestKit with WordSpecLike {

  "The interaction patterns docs" must {

    "contain a sample for fire and forget" in {
      // #fire-and-forget-definition
      case class PrintMe(message: String)

      val printerBehavior: Behavior[PrintMe] = Behaviors.receive {
        case (context, PrintMe(message)) ⇒
          context.log.info(message)
          Behaviors.same
      }
      // #fire-and-forget-definition

      // #fire-and-forget-doit
      val system = ActorSystem(printerBehavior, "fire-and-forget-sample")

      // note how the system is also the top level actor ref
      val printer: ActorRef[PrintMe] = system

      // these are all fire and forget
      printer ! PrintMe("message 1")
      printer ! PrintMe("not message 2")
      // #fire-and-forget-doit

      system.terminate().futureValue
    }

    "contain a sample for request response" in {

      // #request-response-protocol
      case class Request(query: String, respondTo: ActorRef[Response])
      case class Response(result: String)
      // #request-response-protocol

      // #request-response-respond
      val otherBehavior = Behaviors.receiveMessage[Request] {
        case Request(query, respondTo) ⇒
          // ... process query ...
          respondTo ! Response("Here's your cookies!")
          Behaviors.same
      }
      // #request-response-respond

      val otherActor: ActorRef[Request] = spawn(otherBehavior)
      val probe = TestProbe[Response]()
      // shhh, don't tell anyone
      import scala.language.reflectiveCalls
      val context = new {
        def self = probe.ref
      }
      // #request-response-send
      otherActor ! Request("give me cookies", context.self)
      // #request-response-send

      probe.receiveMessage()
    }

    "contain a sample for adapted response" in {
      // #adapted-response

      object Backend {
        sealed trait Request
        final case class StartTranslationJob(
          taskId:  Int,
          site:    URI,
          replyTo: ActorRef[Response]
        ) extends Request

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
          Behaviors.setup[Command] { context ⇒
            val backendResponseMapper: ActorRef[Backend.Response] =
              context.messageAdapter(rsp ⇒ WrappedBackendResponse(rsp))

            def active(
              inProgress: Map[Int, ActorRef[URI]],
              count:      Int): Behavior[Command] = {
              Behaviors.receiveMessage[Command] {
                case Translate(site, replyTo) ⇒
                  val taskId = count + 1
                  backend ! Backend.StartTranslationJob(taskId, site, backendResponseMapper)
                  active(inProgress.updated(taskId, replyTo), taskId)

                case wrapped: WrappedBackendResponse ⇒ wrapped.response match {
                  case Backend.JobStarted(taskId) ⇒
                    context.log.info("Started {}", taskId)
                    Behaviors.same
                  case Backend.JobProgress(taskId, progress) ⇒
                    context.log.info("Progress {}: {}", taskId, progress)
                    Behaviors.same
                  case Backend.JobCompleted(taskId, result) ⇒
                    context.log.info("Completed {}: {}", taskId, result)
                    inProgress(taskId) ! result
                    active(inProgress - taskId, count)
                }
              }
            }

            active(inProgress = Map.empty, count = 0)
          }
      }
      // #adapted-response

      val backend = spawn(Behaviors.receiveMessage[Backend.Request] {
        case Backend.StartTranslationJob(taskId, site, replyTo) ⇒
          replyTo ! Backend.JobStarted(taskId)
          replyTo ! Backend.JobProgress(taskId, 0.25)
          replyTo ! Backend.JobProgress(taskId, 0.50)
          replyTo ! Backend.JobProgress(taskId, 0.75)
          replyTo ! Backend.JobCompleted(taskId, new URI("https://akka.io/docs/sv/"))
          Behaviors.same
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
    case class ExcitingMessage(message: String) extends Msg
    final case class Batch(messages: Vector[Msg])
    case object Timeout extends Msg

    def behavior(target: ActorRef[Batch], after: FiniteDuration, maxSize: Int): Behavior[Msg] = {
      Behaviors.withTimers(timers ⇒ idle(timers, target, after, maxSize))
    }

    def idle(timers: TimerScheduler[Msg], target: ActorRef[Batch],
             after: FiniteDuration, maxSize: Int): Behavior[Msg] = {
      Behaviors.receiveMessage[Msg] { message ⇒
        timers.startSingleTimer(TimerKey, Timeout, after)
        active(Vector(message), timers, target, after, maxSize)
      }
    }

    def active(buffer: Vector[Msg], timers: TimerScheduler[Msg],
               target: ActorRef[Batch], after: FiniteDuration, maxSize: Int): Behavior[Msg] = {
      Behaviors.receiveMessage[Msg] {
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
    sealed trait HalCommand
    case class OpenThePodBayDoorsPlease(respondTo: ActorRef[HalResponse]) extends HalCommand
    case class HalResponse(message: String)

    val halBehavior = Behaviors.receiveMessage[HalCommand] {
      case OpenThePodBayDoorsPlease(respondTo) ⇒
        respondTo ! HalResponse("I'm sorry, Dave. I'm afraid I can't do that.")
        Behaviors.same
    }

    sealed trait DaveMessage
    // this is a part of the protocol that is internal to the actor itself
    case class AdaptedResponse(message: String) extends DaveMessage

    def daveBehavior(hal: ActorRef[HalCommand]) = Behaviors.setup[DaveMessage] { context ⇒

      // asking someone requires a timeout, if the timeout hits without response
      // the ask is failed with a TimeoutException
      implicit val timeout: Timeout = 3.seconds

      // Note: The second parameter list takes a function `ActorRef[T] => Message`,
      // as OpenThePodBayDoorsPlease is a case class it has a factory apply method
      // that is what we are passing as the second parameter here it could also be written
      // as `ref => OpenThePodBayDoorsPlease(ref)`
      context.ask(hal)(OpenThePodBayDoorsPlease) {
        case Success(HalResponse(message)) ⇒ AdaptedResponse(message)
        case Failure(ex)                   ⇒ AdaptedResponse("Request failed")
      }

      // we can also tie in request context into an interaction, it is safe to look at
      // actor internal state from the transformation function, but remember that it may have
      // changed at the time the response arrives and the transformation is done, best is to
      // use immutable state we have closed over like here.
      val requestId = 1
      context.ask(hal)(OpenThePodBayDoorsPlease) {
        case Success(HalResponse(message)) ⇒ AdaptedResponse(s"$requestId: $message")
        case Failure(ex)                   ⇒ AdaptedResponse(s"$requestId: Request failed")
      }

      Behaviors.receiveMessage {
        // the adapted message ends up being processed like any other
        // message sent to the actor
        case AdaptedResponse(message) ⇒
          context.log.info("Got response from hal: {}", message)
          Behaviors.same
      }
    }
    // #actor-ask

    // somewhat modified behavior to let us know we saw the two requests
    val monitor = TestProbe[HalCommand]()
    val hal = spawn(Behaviors.monitor(monitor.ref, halBehavior))
    spawn(daveBehavior(hal))
    monitor.expectMessageType[OpenThePodBayDoorsPlease]
    monitor.expectMessageType[OpenThePodBayDoorsPlease]
  }

  "contain a sample for per session child" in {
    // #per-session-child
    // dummy data types just for this sample
    case class Keys()
    case class Wallet()

    // #per-session-child

    val keyCabinetBehavior: Behavior[GetKeys] = Behaviors.receiveMessage {
      case GetKeys(_, respondTo) ⇒
        respondTo ! Keys()
        Behaviors.same
    }
    val drawerBehavior: Behavior[GetWallet] = Behaviors.receiveMessage {
      case GetWallet(_, respondTo) ⇒
        respondTo ! Wallet()
        Behaviors.same
    }

    // #per-session-child
    // messages for the two services we interact with
    trait HomeCommand
    case class LeaveHome(who: String, respondTo: ActorRef[ReadyToLeaveHome]) extends HomeCommand
    case class ReadyToLeaveHome(who: String, keys: Keys, wallet: Wallet)

    case class GetKeys(whoseKeys: String, respondTo: ActorRef[Keys])
    case class GetWallet(whoseWallet: String, respondTo: ActorRef[Wallet])

    def homeBehavior = Behaviors.receive[HomeCommand] { (context, message) ⇒
      val keyCabinet: ActorRef[GetKeys] = context.spawn(keyCabinetBehavior, "key-cabinet")
      val drawer: ActorRef[GetWallet] = context.spawn(drawerBehavior, "drawer")

      message match {
        case LeaveHome(who, respondTo) ⇒
          context.spawn(prepareToLeaveHome(who, respondTo, keyCabinet, drawer), s"leaving-$who")
          Behavior.same
      }
    }

    // per session actor behavior
    def prepareToLeaveHome(
      whoIsLeaving: String,
      respondTo:    ActorRef[ReadyToLeaveHome],
      keyCabinet:   ActorRef[GetKeys],
      drawer:       ActorRef[GetWallet]): Behavior[NotUsed] =
      // we don't _really_ care about the actor protocol here as nobody will send us
      // messages except for responses to our queries, so we just accept any kind of message
      // but narrow that to more limited types then we interact
      Behaviors.setup[AnyRef] { context ⇒
        var wallet: Option[Wallet] = None
        var keys: Option[Keys] = None

        // we narrow the ActorRef type to any subtype of the actual type we accept
        keyCabinet ! GetKeys(whoIsLeaving, context.self.narrow[Keys])
        drawer ! GetWallet(whoIsLeaving, context.self.narrow[Wallet])

        def nextBehavior: Behavior[AnyRef] =
          (keys, wallet) match {
            case (Some(w), Some(k)) ⇒
              // we got both, "session" is completed!
              respondTo ! ReadyToLeaveHome(whoIsLeaving, w, k)
              Behavior.stopped

            case _ ⇒
              Behavior.same
          }

        Behaviors.receiveMessage {
          case w: Wallet ⇒
            wallet = Some(w)
            nextBehavior
          case k: Keys ⇒
            keys = Some(k)
            nextBehavior
          case _ ⇒
            Behaviors.unhandled
        }
      }.narrow[NotUsed] // we don't let anyone else know we accept anything
    // #per-session-child

    val requestor = TestProbe[ReadyToLeaveHome]()

    val home = spawn(homeBehavior, "home")
    home ! LeaveHome("Bobby", requestor.ref)
    requestor.expectMessage(ReadyToLeaveHome("Bobby", Keys(), Wallet()))
  }

  "contain a sample for ask from outside the actor system" in {
    // #standalone-ask
    trait CookieCommand {}
    case class GiveMeCookies(replyTo: ActorRef[Cookies]) extends CookieCommand
    case class Cookies(count: Int)
    // #standalone-ask

    // keep this out of the sample as it uses the testkit spawn
    val cookieActorRef = spawn(Behaviors.receiveMessage[GiveMeCookies] { message ⇒
      message.replyTo ! Cookies(5)
      Behaviors.same
    })

    // #standalone-ask

    import akka.actor.typed.scaladsl.AskPattern._

    // asking someone requires a timeout and a scheduler, if the timeout hits without response
    // the ask is failed with a TimeoutException
    implicit val timeout: Timeout = 3.seconds
    implicit val scheduler = system.scheduler

    val result: Future[Cookies] = cookieActorRef ? (ref ⇒ GiveMeCookies(ref))

    // the response callback will be executed on this execution context
    implicit val ec = system.executionContext

    result.onComplete {
      case Success(cookies) ⇒ println("Yay, cookies!")
      case Failure(ex)      ⇒ println("Boo! didn't get cookies in time.")
    }
    // #standalone-ask

    result.futureValue shouldEqual Cookies(5)
  }
}
