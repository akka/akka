/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.ws

import scala.concurrent.{ Promise, Future }
import scala.util.{ Try, Failure, Success }

import spray.json._

import akka.actor.ActorSystem

import akka.stream.ActorMaterializer
import akka.stream.stage.{ TerminationDirective, Context, SyncDirective, PushStage }
import akka.stream.scaladsl._

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.ws._

object WSClientAutobahnTest extends App {
  implicit val system = ActorSystem()
  import system.dispatcher
  implicit val materializer = ActorMaterializer()

  val Agent = "akka-http"
  val Parallelism = 4

  val getCaseCountUri: Uri =
    s"ws://localhost:9001/getCaseCount"

  def runCaseUri(caseIndex: Int, agent: String): Uri =
    s"ws://localhost:9001/runCase?case=$caseIndex&agent=$agent"

  def getCaseStatusUri(caseIndex: Int, agent: String): Uri =
    s"ws://localhost:9001/getCaseStatus?case=$caseIndex&agent=$agent"

  def getCaseInfoUri(caseIndex: Int): Uri =
    s"ws://localhost:9001/getCaseInfo?case=$caseIndex"

  def updateReportsUri(agent: String): Uri =
    s"ws://localhost:9001/updateReports?agent=$agent"

  def runCase(caseIndex: Int, agent: String = Agent): Future[CaseStatus] =
    runWs(runCaseUri(caseIndex, agent), echo).recover { case _ ⇒ () }.flatMap { _ ⇒
      getCaseStatus(caseIndex, agent)
    }

  def richRunCase(caseIndex: Int, agent: String = Agent): Future[CaseResult] = {
    val info = getCaseInfo(caseIndex)
    val startMillis = System.currentTimeMillis()
    val status = runCase(caseIndex, agent).map { res ⇒
      val lastedMillis = System.currentTimeMillis() - startMillis
      (res, lastedMillis)
    }
    import Console._
    info.flatMap { i ⇒
      val prefix = f"$YELLOW${i.caseInfo.id}%-7s$RESET - $RESET${i.caseInfo.description}$RESET ... "

      status.onComplete {
        case Success((CaseStatus(status), millis)) ⇒
          val color = if (status == "OK") GREEN else RED
          println(f"${color}$status%-15s$RESET$millis%5d ms $prefix")
        case Failure(e) ⇒
          println(s"$prefix${RED}failed with '${e.getMessage}'$RESET")
      }

      status.map(s ⇒ CaseResult(i.caseInfo, s._1))
    }
  }

  def getCaseCount(): Future[Int] =
    runToSingleText(getCaseCountUri).map(_.toInt)

  def getCaseInfo(caseId: Int): Future[IndexedCaseInfo] =
    runToSingleJsonValue[CaseInfo](getCaseInfoUri(caseId)).map(IndexedCaseInfo(caseId, _))

  def getCaseStatus(caseId: Int, agent: String = Agent): Future[CaseStatus] =
    runToSingleJsonValue[CaseStatus](getCaseStatusUri(caseId, agent))

  def updateReports(agent: String = Agent): Future[Unit] =
    runToSingleText(updateReportsUri(agent)).map(_ ⇒ ())

  /**
   * Map from textual case ID (like 1.1.1) to IndexedCaseInfo
   * @return
   */
  def getCaseMap(): Future[Map[String, IndexedCaseInfo]] = {
    val res =
      getCaseCount().flatMap { count ⇒
        println(s"Retrieving case info for $count cases...")
        Future.traverse(1 to count)(getCaseInfo).map(_.map(e ⇒ e.caseInfo.id → e).toMap)
      }
    res.foreach { res ⇒
      println(s"Received info for ${res.size} cases")
    }
    res
  }

  def echo = Flow[Message].viaMat(completionSignal)(Keep.right)

  import Console._
  if (args.size >= 1) {
    // run one
    val testId = args(0)
    println(s"Trying to run test $testId")
    getCaseMap().flatMap { map ⇒
      val info = map(testId)
      richRunCase(info.index)
    }.onComplete {
      case Success(res) ⇒
        println(s"[OK] Run successfully finished!")
        updateReportsAndShutdown()
      case Failure(e) ⇒
        println(s"[${RED}FAILED$RESET] Run failed with this exception: ")
        e.printStackTrace()
        updateReportsAndShutdown()
    }
  } else {
    println("Running complete test suite")
    getCaseCount().flatMap { count ⇒
      println(s"Found $count tests.")
      Source(1 to count).mapAsyncUnordered(Parallelism)(richRunCase(_)).grouped(count).runWith(Sink.head)
    }.map { results ⇒
      val grouped =
        results.groupBy(_.status.behavior)

      println(s"${results.size} tests run.")
      println()
      println(s"${GREEN}OK$RESET: ${grouped.getOrElse("OK", Nil).size}")
      val notOk = grouped.filterNot(_._1 == "OK")
      notOk.toSeq.sortBy(_._2.size).foreach {
        case (status, cases) ⇒ println(s"$RED$status$RESET: ${cases.size}")
      }
      println()
      println("Not OK tests: ")
      println()
      results.filterNot(_.status.behavior == "OK").foreach { r ⇒
        println(f"$RED${r.status.behavior}%-20s$RESET $YELLOW${r.info.id}%-7s$RESET - $RESET${r.info.description}")
      }

      ()
    }
      .onComplete(completion)
  }

  def completion[T]: Try[T] ⇒ Unit = {
    case Success(res) ⇒
      println(s"Run successfully finished!")
      updateReportsAndShutdown()
    case Failure(e) ⇒
      println("Run failed with this exception")
      e.printStackTrace()
      updateReportsAndShutdown()
  }
  def updateReportsAndShutdown(): Unit =
    updateReports().onComplete { res ⇒
      println("Reports should now be accessible at http://localhost:8080/cwd/reports/clients/index.html")
      system.terminate()
    }

  import scala.concurrent.duration._
  import system.dispatcher
  system.scheduler.scheduleOnce(60.seconds)(system.terminate())

  def runWs[T](uri: Uri, clientFlow: Flow[Message, Message, T]): T =
    Http().singleWebSocketRequest(uri, clientFlow)._2

  def completionSignal[T]: Flow[T, T, Future[Unit]] =
    Flow[T].transformMaterializing { () ⇒
      val p = Promise[Unit]()
      val stage =
        new PushStage[T, T] {
          def onPush(elem: T, ctx: Context[T]): SyncDirective = ctx.push(elem)
          override def onUpstreamFinish(ctx: Context[T]): TerminationDirective = {
            p.success(())
            super.onUpstreamFinish(ctx)
          }
          override def onDownstreamFinish(ctx: Context[T]): TerminationDirective = {
            p.success(()) // should this be failure as well?
            super.onDownstreamFinish(ctx)
          }
          override def onUpstreamFailure(cause: Throwable, ctx: Context[T]): TerminationDirective = {
            p.failure(cause)
            super.onUpstreamFailure(cause, ctx)
          }
        }

      (stage, p.future)
    }

  /**
   * The autobahn tests define a weird API where every request must be a WebSocket request and
   * they will send a single websocket message with the result. WebSocket everywhere? Strange,
   * but somewhat consistent.
   */
  def runToSingleText(uri: Uri): Future[String] = {
    val sink = Sink.head[Message]
    runWs(uri, Flow.fromSinkAndSourceMat(sink, Source.maybe[Message])(Keep.left)).flatMap {
      case tm: TextMessage ⇒ tm.textStream.runWith(Sink.fold("")(_ + _))
      case other ⇒
        throw new IllegalStateException(s"unexpected element of type ${other.getClass}")
    }
  }
  def runToSingleJsonValue[T: JsonReader](uri: Uri): Future[T] =
    runToSingleText(uri).map(_.parseJson.convertTo[T])

  case class IndexedCaseInfo(index: Int, caseInfo: CaseInfo)
  case class CaseResult(info: CaseInfo, status: CaseStatus)

  // {"behavior": "OK"}
  case class CaseStatus(behavior: String) {
    def isSuccessful: Boolean = behavior == "OK"
  }
  object CaseStatus {
    import DefaultJsonProtocol._
    implicit def caseStatusFormat: JsonFormat[CaseStatus] = jsonFormat1(CaseStatus.apply)
  }

  // {"id": "1.1.1", "description": "Send text message with payload 0."}
  case class CaseInfo(id: String, description: String)
  object CaseInfo {
    import DefaultJsonProtocol._
    implicit def caseInfoFormat: JsonFormat[CaseInfo] = jsonFormat2(CaseInfo.apply)
  }
}
