/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.akka.typed

import java.net.URI

import akka.actor.typed.{ ActorRef, ActorSystem, Behavior, TypedAkkaSpecWithShutdown }
import akka.actor.typed.scaladsl.Behaviors
import akka.testkit.typed.TestKit
import akka.testkit.typed.scaladsl.TestProbe

class InteractionPatternsSpec extends TestKit with TypedAkkaSpecWithShutdown {

  "The interaction patterns docs" must {

    "contain a sample for fire and forget" in {
      // #fire-and-forget

      sealed trait PrinterProtocol
      case object DisableOutput extends PrinterProtocol
      case object EnableOutput extends PrinterProtocol
      case class PrintMe(message: String) extends PrinterProtocol

      // two state behavior
      def enabledPrinterBehavior: Behavior[PrinterProtocol] = Behaviors.immutable {
        case (_, DisableOutput) ⇒
          disabledPrinterBehavior

        case (_, EnableOutput) ⇒
          Behaviors.ignore

        case (_, PrintMe(message)) ⇒
          println(message)
          Behaviors.same
      }

      def disabledPrinterBehavior: Behavior[PrinterProtocol] = Behaviors.immutable {
        case (_, DisableOutput) ⇒
          enabledPrinterBehavior

        case (_, _) ⇒
          // ignore any message
          Behaviors.ignore
      }

      val system = ActorSystem(enabledPrinterBehavior, "fire-and-forget-sample")

      // note how the system is also the top level actor ref
      val printer: ActorRef[PrinterProtocol] = system

      // these are all fire and forget
      printer ! PrintMe("printed")
      printer ! DisableOutput
      printer ! PrintMe("not printed")
      printer ! EnableOutput

      // #fire-and-forget

      system.terminate().futureValue
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

      }
      )

      val frontend = spawn(Frontend.translator(backend))
      val probe = TestProbe[URI]()
      frontend ! Frontend.Translate(new URI("https://akka.io/docs/"), probe.ref)
      probe.expectMsg(new URI("https://akka.io/docs/sv/"))

    }

  }

}
