/* scala-stm - (c) 2009-2011, Stanford University, PPL */

package scala.concurrent.stm.japi

import java.util.concurrent.Callable
import java.util.{ List ⇒ JList, Map ⇒ JMap, Set ⇒ JSet }
import scala.collection.JavaConversions
import scala.concurrent.stm
import scala.concurrent.stm._
import scala.runtime.AbstractFunction1

/**
 * Java-friendly API for ScalaSTM.
 * These methods can also be statically imported.
 */
object Stm {

  /**
   * Create a Ref with an initial value. Return a `Ref.View`, which does not
   * require implicit transactions.
   * @param initialValue the initial value for the newly created `Ref.View`
   * @return a new `Ref.View`
   */
  def newRef[A](initialValue: A): Ref.View[A] = Ref(initialValue).single

  /**
   * Create an empty TMap. Return a `TMap.View`, which does not require
   * implicit transactions. See newMap for included java conversion.
   * @return a new, empty `TMap.View`
   */
  def newTMap[A, B](): TMap.View[A, B] = TMap.empty[A, B].single

  /**
   * Create an empty TMap. Return a `java.util.Map` view of this TMap.
   * @return a new, empty `TMap.View` wrapped as a `java.util.Map`.
   */
  def newMap[A, B](): JMap[A, B] = JavaConversions.mutableMapAsJavaMap(newTMap[A, B])

  /**
   * Create an empty TSet. Return a `TSet.View`, which does not require
   * implicit transactions. See newSet for included java conversion.
   * @return a new, empty `TSet.View`
   */
  def newTSet[A](): TSet.View[A] = TSet.empty[A].single

  /**
   * Create an empty TSet. Return a `java.util.Set` view of this TSet.
   * @return a new, empty `TSet.View` wrapped as a `java.util.Set`.
   */
  def newSet[A](): JSet[A] = JavaConversions.mutableSetAsJavaSet(newTSet[A])

  /**
   * Create a TArray containing `length` elements. Return a `TArray.View`,
   * which does not require implicit transactions. See newList for included
   * java conversion.
   * @param length the length of the `TArray.View` to be created
   * @return a new `TArray.View` containing `length` elements (initially null)
   */
  def newTArray[A <: AnyRef](length: Int): TArray.View[A] = TArray.ofDim[A](length)(ClassManifest.classType(AnyRef.getClass)).single

  /**
   * Create an empty TArray. Return a `java.util.List` view of this Array.
   * @param length the length of the `TArray.View` to be created
   * @return a new, empty `TArray.View` wrapped as a `java.util.List`.
   */
  def newList[A <: AnyRef](length: Int): JList[A] = JavaConversions.mutableSeqAsJavaList(newTArray[A](length))

  /**
   * Atomic block that takes a `Runnable`.
   * @param runnable the `Runnable` to run within a transaction
   */
  def atomic(runnable: Runnable): Unit = stm.atomic { txn ⇒ runnable.run }

  /**
   * Atomic block that takes a `Callable`.
   * @param callable the `Callable` to run within a transaction
   * @return the value returned by the `Callable`
   */
  def atomic[A](callable: Callable[A]): A = stm.atomic { txn ⇒ callable.call }

  /**
   * Transform the value stored by `ref` by applying the function `f`.
   * @param ref the `Ref.View` to be transformed
   * @param f the function to be applied
   */
  def transform[A](ref: Ref.View[A], f: AbstractFunction1[A, A]): Unit = ref.transform(f)

  /**
   * Transform the value stored by `ref` by applying the function `f` and
   * return the old value.
   * @param ref the `Ref.View` to be transformed
   * @param f the function to be applied
   * @return the old value of `ref`
   */
  def getAndTransform[A](ref: Ref.View[A], f: AbstractFunction1[A, A]): A = ref.getAndTransform(f)

  /**
   * Transform the value stored by `ref` by applying the function `f` and
   * return the new value.
   * @param ref the `Ref.View` to be transformed
   * @param f the function to be applied
   * @return the new value of `ref`
   */
  def transformAndGet[A](ref: Ref.View[A], f: AbstractFunction1[A, A]): A = ref.transformAndGet(f)

  /**
   * Increment the `java.lang.Integer` value of a `Ref.View`.
   * @param ref the `Ref.View<Integer>` to be incremented
   * @param delta the amount to increment
   */
  def increment(ref: Ref.View[java.lang.Integer], delta: Int): Unit = ref.transform { v ⇒ v.intValue + delta }

  /**
   * Increment the `java.lang.Long` value of a `Ref.View`.
   * @param ref the `Ref.View<Long>` to be incremented
   * @param delta the amount to increment
   */
  def increment(ref: Ref.View[java.lang.Long], delta: Long): Unit = ref.transform { v ⇒ v.longValue + delta }

  /**
   * Add a task to run after the current transaction has committed.
   * @param task the `Runnable` task to run after transaction commit
   */
  def afterCommit(task: Runnable): Unit = {
    val txn = Txn.findCurrent
    if (txn.isDefined) Txn.afterCommit(status ⇒ task.run)(txn.get)
  }

  /**
   * Add a task to run after the current transaction has rolled back.
   * @param task the `Runnable` task to run after transaction rollback
   */
  def afterRollback(task: Runnable): Unit = {
    val txn = Txn.findCurrent
    if (txn.isDefined) Txn.afterRollback(status ⇒ task.run)(txn.get)
  }

  /**
   * Add a task to run after the current transaction has either rolled back
   * or committed.
   * @param task the `Runnable` task to run after transaction completion
   */
  def afterCompletion(task: Runnable): Unit = {
    val txn = Txn.findCurrent
    if (txn.isDefined) Txn.afterCompletion(status ⇒ task.run)(txn.get)
  }
}
