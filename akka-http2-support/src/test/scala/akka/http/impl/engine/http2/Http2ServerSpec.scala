package akka.http.impl.engine.http2

import akka.stream.ActorMaterializer
import akka.testkit.AkkaSpec

class Http2ServerSpec extends AkkaSpec {
  implicit val mat = ActorMaterializer()

  "The Http/2 server implementation" should {
    "xyz" in pending
  }
}
