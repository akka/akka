package akka.streams.impl

import rx.async.api.Producer

trait InternalProducer[O] extends Producer[O] {
  def createSource(downstream: Downstream[O]): SyncSource
}
