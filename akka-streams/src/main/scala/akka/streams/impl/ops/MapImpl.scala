package akka.streams.impl.ops

import akka.streams.impl._
import scala.util.control.NonFatal

class MapImpl[I, O](val upstream: Upstream, val downstream: Downstream[O], f: I ⇒ O)
  extends MapLikeImpl[I, O] {
  def map(i: I): O = f(i)
}
