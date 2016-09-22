package akka.http.impl.engine.http2

import akka.stream.ActorMaterializer
import akka.testkit.AkkaSpec

class Http2ServerSpec extends AkkaSpec {
  implicit val mat = ActorMaterializer()

  "The Http/2 server implementation" should {
    "respect settings" should {
      "initial MAX_FRAME_SIZE" in pending
      "received SETTINGS_MAX_FRAME_SIZE" in pending

      "not exceed connection-level window while sending" in pending
      "not exceed stream-level window while sending" in pending
      "not exceed stream-level window while sending after SETTINGS_INITIAL_WINDOW_SIZE changed" in pending
      "not exceed stream-level window while sending after SETTINGS_INITIAL_WINDOW_SIZE changed when window became negative through setting" in pending

      "received SETTINGS_MAX_CONCURRENT_STREAMS" in pending
    }

    "support low-level features" should {
      "eventually send WINDOW_UPDATE frames for received data" in pending
      "respond to PING frames" in pending
      "acknowledge SETTINGS frames" in pending
    }

    "respect the substream state machine" should {
      "reject other frame than HEADERS/PUSH_PROMISE in idle state with connection-level PROTOCOL_ERROR (5.1)" in pending
      "reject incoming frames on already half-closed substream" in pending

      "reject even-numbered client-initiated substreams" in pending

      "reject double sub-streams creation" in pending
      "reject substream creation for streams invalidated by skipped substream IDs" in pending
    }

    "support multiple concurrent substreams" should {
      "receive two requests concurrently" in pending
      "send two responses concurrently" in pending
    }
  }
}
