package akka.http.rendering

import org.scalatest.WordSpec

class RenderingSpec extends WordSpec {
  "The StringRendering" should {
    "correctly render Ints and Longs to decimal" in {
      (new StringRendering ~~ 0).get === "0"
      (new StringRendering ~~ 123456789).get === "123456789"
      (new StringRendering ~~ -123456789L).get === "-123456789"
    }
    "correctly render Ints and Longs to hex" in {
      (new StringRendering ~~% 0).get === "0"
      (new StringRendering ~~% 65535).get === "ffff"
      (new StringRendering ~~% 65537).get === "10001"
      (new StringRendering ~~% -10L).get === "fffffffffffffff6"
    }
    "correctly render plain Strings" in {
      (new StringRendering ~~ "").get === ""
      (new StringRendering ~~ "hello").get === "hello"
    }
    "correctly render escaped Strings" in {
      (new StringRendering ~~# "").get === ""
      (new StringRendering ~~# "hello").get === "hello"
      (new StringRendering ~~# """hel"lo""").get === """"hel\"lo""""
    }
  }
}
