package scala.concurrent.impl

import scala.concurrent.ExecutionContext

object InternalFutureUtil {
  @inline final def releaseFutureStack(ec: ExecutionContext): Unit = Future.releaseStack(ec)
  @inline final def canAwaitEvidence = scala.concurrent.Await.canAwaitEvidence
}