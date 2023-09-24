/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.actor.typed

import akka.actor.IllegalActorStateException
import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.AskPattern.schedulerFromActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.Behaviors._
import akka.util.Timeout
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.concurrent.CountDownLatch
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success

sealed trait Command
case class Multiply(a: Int, b: Int, forwardRef: ActorRef[WorkerCommand], replyTo: ActorRef[Int]) extends Command
case class ReplyResult(num: Int, replyTo: ActorRef[Int]) extends Command
case class Ignore() extends Command

sealed trait WorkerCommand
case class WorkerMultiply(a: Int, b: Int, replyTo: ActorRef[WorkerResult]) extends WorkerCommand
case class WorkerResult(num: Int) extends WorkerCommand

class ManualTerminatedTestSetup(val workerLatch: CountDownLatch) {
  implicit val timeout: Timeout = 10.millis

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

  def workerBehavior: Receive[WorkerCommand] =
    Behaviors.receiveMessage[WorkerCommand] { msg =>
      msg match {
        case WorkerMultiply(a, b, replyTo) =>
          workerLatch.await()
          replyTo ! WorkerResult(a * b)
          Behaviors.stopped
        case _ =>
          throw IllegalActorStateException("worker actor should not receive other message.")
      }
    }
}

class DeadLetterSpec extends ScalaTestWithActorTestKit("""
    akka.loglevel=DEBUG
    akka.actor.debug.event-stream = on
    """) with AnyWordSpecLike with LogCapturing {

  implicit def executor: ExecutionContext =
    system.executionContext

  "DeadLetterActor" must {

    "publish dead letter with recipient when context.ask terminated" in new ManualTerminatedTestSetup(
      workerLatch = new CountDownLatch(1)) {
      val deadLetterProbe = createDeadLetterProbe()
      val forwardRef = spawn(forwardBehavior)
      val workerRef = spawn(workerBehavior)

      // this will not completed unit worker reply.
      val multiplyResult: Future[Int] = forwardRef.ask(replyTo => Multiply(3, 9, workerRef, replyTo))
      // waiting for temporary ask actor terminated with timeout
      val result = multiplyResult.failed.futureValue
      result shouldBe a[TimeoutException]
      result.getMessage should startWith("Ask timed out on")
      // unlock worker replying
      workerLatch.countDown()

      val deadLetter = deadLetterProbe.receiveMessage()
      deadLetter.message shouldBe a[WorkerResult]
      val deadLettersRef = system.classicSystem.deadLetters
      // that should be not equals, otherwise, it may raise confusion, perform like a dead letter sent to the deadLetterActor.
      deadLetter.recipient shouldNot equal(deadLettersRef)
    }

    "publish dead letter with recipient when AskPattern timeout" in new ManualTerminatedTestSetup(
      workerLatch = new CountDownLatch(1)) {
      val deadLetterProbe = createDeadLetterProbe()
      val workerRef = spawn(workerBehavior)

      // this not completed unit countDown.
      val multiplyResult: Future[WorkerResult] = workerRef.ask(replyTo => WorkerMultiply(3, 9, replyTo))
      // waiting for temporary ask actor terminated with timeout
      val result = multiplyResult.failed.futureValue
      result shouldBe a[TimeoutException]
      result.getMessage should startWith("Ask timed out on")
      // unlock worker replying
      workerLatch.countDown()

      val deadLetter = deadLetterProbe.receiveMessage()
      deadLetter.message shouldBe a[WorkerResult]
      val deadLettersRef = system.classicSystem.deadLetters
      // that should be not equals, otherwise, it may raise confusion, perform like a dead letter sent to the deadLetterActor.
      deadLetter.recipient shouldNot equal(deadLettersRef)
    }
  }
}
