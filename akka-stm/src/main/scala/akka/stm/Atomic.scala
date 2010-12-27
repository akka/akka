/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.stm

/**
 * Java-friendly atomic blocks.
 *
 * Example usage ''(Java)''
 *
 * {{{
 * import akka.stm.*;
 *
 * final Ref<Integer> ref = new Ref<Integer>(0);
 *
 * new Atomic() {
 *     public Object atomically() {
 *         return ref.set(1);
 *     }
 * }.execute();
 *
 * // To configure transactions pass a TransactionFactory
 *
 * TransactionFactory txFactory = new TransactionFactoryBuilder()
 *     .setReadonly(true)
 *     .build();
 *
 * Integer value = new Atomic<Integer>(txFactory) {
 *     public Integer atomically() {
 *         return ref.get();
 *     }
 * }.execute();
 * }}}
 */
abstract class Atomic[T](val factory: TransactionFactory) {
  def this() = this(DefaultTransactionFactory)
  def atomically: T
  def execute: T = atomic(factory)(atomically)
}
