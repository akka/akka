package akka.streams.impl

import asyncrx.api.Producer

trait InternalProducer[O] extends Producer[O] {
  // TODO: rename to subscribeDownstream
  def createSource(downstream: Downstream[O]): SyncSource
}
