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

package akka.http.routing

import com.typesafe.config.Config
import akka.actor.ActorRefFactory
import akka.http.util._

case class RoutingSettings(
  verboseErrorMessages: Boolean,
  fileChunkingThresholdSize: Long,
  fileChunkingChunkSize: Int,
  fileGetConditional: Boolean,
  users: Config,
  renderVanityFooter: Boolean,
  rangeCountLimit: Int,
  rangeCoalescingThreshold: Long) {

  require(fileChunkingThresholdSize >= 0, "file-chunking-threshold-size must be >= 0")
  require(fileChunkingChunkSize > 0, "file-chunking-chunk-size must be > 0")
  require(rangeCountLimit >= 0, "range-count-limit must be >= 0")
  require(rangeCoalescingThreshold >= 0, "range-coalescing-threshold must be >= 0")
}

object RoutingSettings extends SettingsCompanion[RoutingSettings]("spray.routing") {
  def fromSubConfig(c: Config) = apply(
    c getBoolean "verbose-error-messages",
    c getBytes "file-chunking-threshold-size",
    c getIntBytes "file-chunking-chunk-size",
    c getBoolean "file-get-conditional",
    c getConfig "users",
    c getBoolean "render-vanity-footer",
    c getInt "range-count-limit",
    c getBytes "range-coalescing-threshold")

  implicit def default(implicit refFactory: ActorRefFactory) =
    apply(actorSystem)
}
