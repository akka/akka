package akka.stream.impl.io

import akka.stream.ActorAttributes
import akka.stream.Attributes

private[stream] object IOSettings {

  final val SyncFileSourceDefaultChunkSize = 8192
  final val SyncFileSourceName = Attributes.name("synchronousFileSource")
  final val SyncFileSinkName = Attributes.name("synchronousFileSink")
  final val IODispatcher = ActorAttributes.Dispatcher("akka.stream.default-blocking-io-dispatcher")
}
