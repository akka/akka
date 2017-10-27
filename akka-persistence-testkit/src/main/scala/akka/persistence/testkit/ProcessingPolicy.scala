/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit

import akka.annotation.InternalApi

/**
 * Policies allow to emulate behavior of the storage (failures and rejections).
 *
 * @tparam U type determines operations which storage can perform.
 */
trait ProcessingPolicy[U] {

  import ProcessingPolicy._

  /**
   * Emulates behavior of the storage.
   * The function is invoked when any of the plugin's operations is executed.
   * If you need this operation to succeed return [[ProcessingSuccess]],
   * otherwise you should return some of the [[ProcessingFailure]]'s.
   *
   * @param processingUnit details about current operation to be executed
   * @return needed result of processing the operation
   */
  def tryProcess(persistenceId: String, processingUnit: U): ProcessingResult

}

object ProcessingPolicy {

  /**
   * INTERNAL API
   */
  @InternalApi
  private[testkit] trait DefaultPolicies[U] {

    type PolicyType = ProcessingPolicy[U]

    case object PassAll extends PolicyType {

      override def tryProcess(persistenceId: String, processingUnit: U): ProcessingResult = ProcessingSuccess

    }

    class RejectNextNCond(_numberToFail: Int, _failureException: Throwable, cond: (String, U) ⇒ Boolean, onLimitExceed: ⇒ Unit = ()) extends CountNextNCond(_numberToFail, Reject(_failureException), ProcessingSuccess, cond, onLimitExceed)

    class FailNextNCond(_numberToFail: Int, _failureException: Throwable, cond: (String, U) ⇒ Boolean, onLimitExceed: ⇒ Unit = ()) extends CountNextNCond(_numberToFail, StorageFailure(_failureException), ProcessingSuccess, cond, onLimitExceed)

    class FailNextN(_numberToFail: Int, _failureException: Throwable, onLimitExceed: ⇒ Unit = ()) extends CountNextNCond(_numberToFail, StorageFailure(_failureException), ProcessingSuccess, (_, _) ⇒ true, onLimitExceed)

    class RejectNextN(_numberToReject: Int, _rejectionException: Throwable, onLimitExceed: ⇒ Unit = ()) extends CountNextNCond(_numberToReject, Reject(_rejectionException), ProcessingSuccess, (_, _) ⇒ true, onLimitExceed)

    class ReturnAfterNextNCond(returnOnTrigger: ⇒ ProcessingResult, returnNonTrigger: ⇒ ProcessingResult, cond: (String, U) ⇒ Boolean) extends PolicyType {

      override def tryProcess(persistenceId: String, processingUnit: U): ProcessingResult = {
        if (cond(persistenceId, processingUnit)) {
          returnOnTrigger
        } else {
          returnNonTrigger
        }
      }

    }

    class CountNextNCond(numberToCount: Int, returnOnTrigger: ⇒ ProcessingResult, returnNonTrigger: ⇒ ProcessingResult, cond: (String, U) ⇒ Boolean, onLimitExceed: ⇒ Unit) extends ReturnAfterNextNCond(returnOnTrigger, returnNonTrigger, new Function2[String, U, Boolean] {

      var counter = 0

      override def apply(persistenceId: String, v1: U): Boolean = {
        val intRes = cond(persistenceId, v1)
        if (intRes && counter < numberToCount) {
          counter += 1
          if (counter == numberToCount) onLimitExceed
          intRes
        } else {
          false
        }
      }
    })

  }

  sealed trait ProcessingResult

  /**
   * Emulates successful processing of some operation.
   */
  object ProcessingSuccess extends ProcessingResult {

    def getInstance() = this

  }

  sealed trait ProcessingFailure extends ProcessingResult {

    def error: Throwable

  }

  object ExpectedRejection extends Throwable {

    def getInstance() = this

  }
  object ExpectedFailure extends Throwable {

    def getInstance() = this

  }

  /**
   * Emulates rejection of operation by the journal with `error` exception.
   * Has the same meaning as `StorageFailure` for snapshot storage,
   * because it does not support rejections.
   */
  sealed case class Reject(error: Throwable = ExpectedRejection) extends ProcessingFailure {

    def getError() = error

  }

  object Reject {

    def create(error: Throwable) = Reject(error)

    def create() = Reject(ExpectedRejection)

  }

  /**
   * Emulates exception thrown by the storage on the attempt to perform some operation.
   */
  sealed case class StorageFailure(error: Throwable = ExpectedFailure) extends ProcessingFailure {

    def getError() = error

  }

  object StorageFailure {

    def create(error: Throwable) = StorageFailure(error)

    def create() = StorageFailure(ExpectedFailure)

  }

}

