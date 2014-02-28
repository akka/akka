package akka.streams.impl.ops

import akka.streams.impl.{ Continue, Downstream, Effect, SyncSource }

class EmptySourceImpl(downstream: Downstream[_]) extends SyncSource {
  override def start(): Effect = downstream.complete

  def handleRequestMore(n: Int): Effect = Continue // ignore
  def handleCancel(): Effect = Continue // ignore
}
