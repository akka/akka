/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.akka.typed

import akka.actor.typed.{ ActorRef, ActorSystem, Behavior, TypedAkkaSpecWithShutdown }
import akka.actor.typed.scaladsl.Behaviors
import akka.testkit.typed.TestKit

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

  }

}
