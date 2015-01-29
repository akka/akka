package docs.stream.cookbook

import akka.stream.ActorFlowMaterializer
import akka.stream.testkit.AkkaSpec

trait RecipeSpec extends AkkaSpec {

  implicit val m = ActorFlowMaterializer()
  type Message = String
  type Trigger = Unit
  type Job = String

}
