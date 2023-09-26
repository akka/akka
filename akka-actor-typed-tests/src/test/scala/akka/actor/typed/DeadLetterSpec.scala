/*
 * Copyright (C) 2017-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.AskPattern.schedulerFromActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.Behaviors._
import akka.util.Timeout
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.math.multiplyExact
import scala.util.Failure
import scala.util.Success

sealed trait Command
case class Multiply(a: Int, b: Int, forwardRef: ActorRef[WorkerCommand], replyTo: ActorRef[Int]) extends Command
case class ReplyResult(num: Int, replyTo: ActorRef[Int]) extends Command
case class Ignore() extends Command

sealed trait WorkerCommand
case class WorkerMultiply(a: Int, b: Int, replyTo: ActorRef[WorkerResult]) extends WorkerCommand
case class WorkerResult(num: Int) extends WorkerCommand

class DeadLetterSpec extends ScalaTestWithActorTestKit("""
    akka.loglevel=DEBUG
    akka.actor.debug.event-stream = on
    """) with AnyWordSpecLike with LogCapturing {

  implicit def executor: ExecutionContext = system.executionContext
  override implicit val timeout: Timeout = 10.millis

  def forwardBehavior: Behavior[Command] =
    setup[Command] { context =>
      Behaviors.receiveMessage[Command] { msg =>
        msg match {
          case Multiply(a, b, ref, replyTo) =>
            // context.ask is asynchronous
            context.ask[WorkerCommand, WorkerResult](ref, resultReply => WorkerMultiply(a, b, resultReply)) {
              case Success(result) => ReplyResult(result.num, replyTo)
              case Failure(_)      => Ignore()
            }
            Behaviors.same
          case ReplyResult(num, replyTo) =>
            replyTo ! num
            Behaviors.same
          case Ignore() => Behaviors.same
        }
      }
    }

  "DeadLetterActor" must {

    "publish dead letter with recipient when context.ask receive tardy reply after timeout" in {
      val forwardRef = spawn(forwardBehavior)
      testDeadLetterPublishWhenAskTimeout[Int](tardyRef => forwardRef.ask(replyTo => Multiply(3, 9, tardyRef, replyTo)))
    }

    "publish dead letter with recipient when AskPattern receive tardy reply after timeout" in {
      testDeadLetterPublishWhenAskTimeout[WorkerResult](tardyRef =>
        tardyRef.ask(replyTo => WorkerMultiply(3, 9, replyTo)))
    }

    def testDeadLetterPublishWhenAskTimeout[T](askFunc: ActorRef[WorkerCommand] => Future[T]): Unit = {
      val deadLetterProbe = createDeadLetterProbe()
      val mockWorkerProbe = createTestProbe[WorkerCommand]()

      // this will not completed unit worker reply.
      val multiplyResult: Future[T] = askFunc(mockWorkerProbe.ref)
      val request = mockWorkerProbe.expectMessageType[WorkerMultiply](1.seconds)
      // waiting for temporary ask actor terminated with timeout
      mockWorkerProbe.expectTerminated(request.replyTo)
      // verify ask timeout
      val result = multiplyResult.failed.futureValue
      result shouldBe a[TimeoutException]
      result.getMessage should startWith("Ask timed out on")
      // mock reply manually
      request match {
        case WorkerMultiply(a, b, replyTo) => replyTo ! WorkerResult(multiplyExact(a, b))
      }

      val deadLetter = deadLetterProbe.receiveMessage()
      deadLetter.message shouldBe a[WorkerResult]
      val deadLettersRef = system.classicSystem.deadLetters
      // that should be not equals, otherwise, it may raise confusion, perform like a dead letter sent to the deadLetterActor.
      deadLetter.recipient shouldNot equal(deadLettersRef)
    }
  }
}
