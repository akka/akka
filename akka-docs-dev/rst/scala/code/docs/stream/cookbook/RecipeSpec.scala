package docs.stream.cookbook

import akka.stream.ActorMaterializer
import akka.stream.testkit.AkkaSpec

trait RecipeSpec extends AkkaSpec {

  implicit val m = ActorMaterializer()
  implicit val ec = m.executionContext
  type Message = String
  type Trigger = Unit
  type Job = String

}
