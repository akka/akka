package akka.streams.impl

import rx.async.api.Producer

trait InternalProducer[O] extends Producer[O] {
  // TODO: rename to subscribeDownstream
  def createSource(downstream: Downstream[O]): SyncSource
}
