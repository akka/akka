package akka.streams.impl

import akka.streams.Operation.CustomSource

case class InternalSource[+O](handler: Downstream[O] ⇒ (SyncSource, Effect)) extends CustomSource[O]
