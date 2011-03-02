/*
 * Copyright 2009 Robey Pointer <robeypointer@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.configgy


/**
 * Interface for receiving notifications of changes to a {@link Config}
 * object. When subscribed to an {@link AttributeMap} node, these methods
 * will be called to validate, and optionally commit, any changes that would
 * affect that node.
 *
 * <p> Changes happen in two phases: First, any affected subscribers are asked
 * to validate the change by having their {@link #validate} method called.
 * If any <code>validate</code> method throws a <code>ValidationException</code>,
 * the change is aborted, and the exception is thrown to the code that tried
 * to make that change. If all of the <code>validate</code> methods return
 * successfully, the {@link #commit} methods will be called to confirm that
 * the change has been validated and is being committed.
 */
trait Subscriber {
  /**
   * Validate a potential change to the subscribed config node. It the node
   * didn't exist prior to this potential change, <code>current</code> will
   * be <code>None</code>. Similarly, if the node is being removed by this
   * change, <code>replacement</code> will be <code>None</code>. Never will
   * both parameters be <code>None</code>.
   *
   * <p> To reject the change, throw <code>ValidationException</code>. A
   * normal return validates the config change and potentially permits it to
   * be committed.
   *
   * @param current the current config node, if it exists
   * @param replacement the new config node, if it will exist
   */
  @throws(classOf[ValidationException])
  def validate(current: Option[ConfigMap], replacement: Option[ConfigMap]): Unit

  /**
   * Commit this change to the subscribed config node. If this method is
   * called, a prior call to <code>validate</code> with these parameters
   * succeeded for all subscribers, and the change is now active. As with
   * <code>validate</code>, either <code>current</code> or
   * <code>replacement</code> (but not both) may be <code>None</code>.
   *
   * @param current the current (now previous) config node, if it existed
   * @param replacement the new (now current) config node, if it exists
   */
  def commit(current: Option[ConfigMap], replacement: Option[ConfigMap]): Unit
}


/**
 * Key returned by a call to <code>AttributeMap.subscribe</code> which may
 * be used to unsubscribe from config change events.
 */
class SubscriptionKey private[configgy](val config: Config, private[configgy] val id: Int) {
  /**
   * Remove the subscription referenced by this key. After unsubscribing,
   * no more validate/commit events will be sent to this subscriber.
   */
  def unsubscribe() = config.unsubscribe(this)
}


/**
 * Exception thrown by <code>Subscriber.validate</code> when a config change
 * must be rejected. If returned by a modification to a {@link Config} or
 * {@link AttributeMap} node, the modification has failed.
 */
class ValidationException(reason: String) extends Exception(reason)
