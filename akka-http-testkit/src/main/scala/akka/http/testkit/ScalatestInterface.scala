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

package akka.http.testkit

import org.scalatest.exceptions.TestFailedException
import org.scalatest.{ Suite, BeforeAndAfterAll }

trait ScalatestInterface extends TestFrameworkInterface with BeforeAndAfterAll {
  this: Suite ⇒

  def failTest(msg: String) = throw new TestFailedException(msg, 11)

  abstract override protected def afterAll(): Unit = {
    cleanUp()
    super.afterAll()
  }
}
