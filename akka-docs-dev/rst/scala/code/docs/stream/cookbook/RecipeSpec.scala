package docs.stream.cookbook

import akka.stream.FlowMaterializer
import akka.stream.testkit.AkkaSpec

trait RecipeSpec extends AkkaSpec {

  implicit val m = FlowMaterializer()
  type Message = String
  type Trigger = Unit
  type Job = String

}
