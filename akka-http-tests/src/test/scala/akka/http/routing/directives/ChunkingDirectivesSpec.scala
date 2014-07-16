/*
 * Copyright © 2011-2013 the spray project <http://spray.io>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.http.routing.directives

import akka.http.model.headers.RawHeader
import akka.http.model.StatusCodes

/*
class ChunkingDirectivesSpec extends RoutingSpec {

  "The `autoChunk` directive" should {
    "produce a correct chunk stream" in {
      val text = "This is a somewhat lengthy text that is being chunked by the autochunk directive!"
      Get() ~> autoChunk(8) { complete(text) } ~> check {
        chunks must haveSize(10)
        val bytes = chunks.foldLeft(body.data.toByteArray)(_ ++ _.data.toByteArray)
        new String(bytes) mustEqual text
      }
    }
    "must reproduce status code and headers of the original response" in {
      val text = "This is a somewhat lengthy text that is being chunked by the autochunk directive!"
      val responseHeader = RawHeader("X-Custom", "Test")
      val route = autoChunk(8) {
        respondWithHeader(responseHeader) {
          complete(StatusCodes.PartialContent, text)
        }
      }

      Get() ~> route ~> check {
        chunks must haveSize(10)
        val bytes = chunks.foldLeft(body.data.toByteArray)(_ ++ _.data.toByteArray)
        new String(bytes) mustEqual text
        status mustEqual StatusCodes.PartialContent
        header("x-custom") mustEqual Some(responseHeader)
      }
    }
  }
}
*/
