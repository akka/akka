package akka.streams.impl

import akka.streams.Operation.CustomSource

// TODO: rename handler to sourceConstructor
case class InternalSource[+O](handler: Downstream[O] ⇒ (SyncSource, Effect)) extends CustomSource[O]
