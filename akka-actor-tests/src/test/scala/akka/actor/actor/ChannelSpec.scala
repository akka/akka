package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import akka.dispatch._

class ChannelSpec extends WordSpec with MustMatchers {
  "A Channel" must {
    "be contravariant" in {
      val ap = new ActorCompletableFuture(1000)
      val p: CompletableFuture[Any] = ap
      val c: Channel[Any] = ap
      val cs: Channel[String] = c
    }
  }
}
