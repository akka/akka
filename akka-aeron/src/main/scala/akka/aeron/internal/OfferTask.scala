/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.aeron.internal

import akka.stream.stage.AsyncCallback
import io.aeron.Publication

import scala.concurrent.duration.{ Duration, FiniteDuration }
import OfferTask._
import akka.annotation.InternalApi
import org.agrona.DirectBuffer

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object OfferTask {
  private val TimerCheckPeriod = 1 << 13 // 8192
  private val TimerCheckMask = TimerCheckPeriod - 1
}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class OfferTask(pub: Publication, var buffer: DirectBuffer, var msgSize: Int, onOfferSuccess: AsyncCallback[Unit],
                                    giveUpAfter: Duration, onGiveUp: AsyncCallback[Unit], onPublicationClosed: AsyncCallback[Unit])
  extends (() ⇒ Boolean) {
  val giveUpAfterNanos = giveUpAfter match {
    case f: FiniteDuration ⇒ f.toNanos
    case _                 ⇒ -1L
  }
  var n = 0L
  var startTime = 0L

  override def apply(): Boolean = {
    if (n == 0L) {
      // first invocation for this message
      startTime = if (giveUpAfterNanos >= 0) System.nanoTime() else 0L
    }
    n += 1
    val result = pub.offer(buffer, 0, msgSize)
    if (result >= 0) {
      n = 0L
      onOfferSuccess.invoke(())
      true
    } else if (result == Publication.CLOSED) {
      onPublicationClosed.invoke(())
      true
    } else if (giveUpAfterNanos >= 0 && (n & TimerCheckMask) == 0 && (System.nanoTime() - startTime) > giveUpAfterNanos) {
      // the task is invoked by the spinning thread, only check nanoTime each 8192th invocation
      n = 0L
      onGiveUp.invoke(())
      true
    } else {
      false
    }
  }
}
