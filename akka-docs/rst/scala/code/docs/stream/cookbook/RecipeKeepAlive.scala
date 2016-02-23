package docs.stream.cookbook

import akka.NotUsed
import akka.stream.ClosedShape
import akka.stream.scaladsl._
import akka.util.ByteString

class RecipeKeepAlive extends RecipeSpec {

  "Recipe for injecting keepalive messages" must {

    "work" in {
      val keepaliveMessage = ByteString(11)

      //#inject-keepalive
      import scala.concurrent.duration._
      val injectKeepAlive: Flow[ByteString, ByteString, NotUsed] =
        Flow[ByteString].keepAlive(1.second, () => keepaliveMessage)
      //#inject-keepalive

      // No need to test, this is a built-in stage with proper tests
    }
  }

}
