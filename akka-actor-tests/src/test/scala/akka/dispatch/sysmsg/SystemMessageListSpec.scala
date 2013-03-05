/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.dispatch.sysmsg

import akka.testkit.AkkaSpec

class SystemMessageListSpec extends AkkaSpec {
  import SystemMessageList.LNil
  import SystemMessageList.ENil

  "The SystemMessageList value class" must {

    "handle empty lists correctly" in {
      LNil.head must be === null
      LNil.isEmpty must be(true)
      (LNil.reverse == ENil) must be(true)
    }

    "able to append messages" in {
      val create0 = Create(0)
      val create1 = Create(1)
      val create2 = Create(2)
      ((create0 :: LNil).head eq create0) must be(true)
      ((create1 :: create0 :: LNil).head eq create1) must be(true)
      ((create2 :: create1 :: create0 :: LNil).head eq create2) must be(true)

      (create2.next eq create1) must be(true)
      (create1.next eq create0) must be(true)
      (create0.next eq null) must be(true)
    }

    "able to deconstruct head and tail" in {
      val create0 = Create(0)
      val create1 = Create(1)
      val create2 = Create(2)
      val list = create2 :: create1 :: create0 :: LNil

      (list.head eq create2) must be(true)
      (list.tail.head eq create1) must be(true)
      (list.tail.tail.head eq create0) must be(true)
      (list.tail.tail.tail.head eq null) must be(true)
    }

    "properly report size and emptyness" in {
      val create0 = Create(0)
      val create1 = Create(1)
      val create2 = Create(2)
      val list = create2 :: create1 :: create0 :: LNil

      list.size must be === 3
      list.isEmpty must be(false)

      list.tail.size must be === 2
      list.tail.isEmpty must be(false)

      list.tail.tail.size must be === 1
      list.tail.tail.isEmpty must be(false)

      list.tail.tail.tail.size must be === 0
      list.tail.tail.tail.isEmpty must be(true)

    }

    "properly reverse contents" in {
      val create0 = Create(0)
      val create1 = Create(1)
      val create2 = Create(2)
      val list = create2 :: create1 :: create0 :: LNil
      val listRev: EarliestFirstSystemMessageList = list.reverse

      listRev.isEmpty must be(false)
      listRev.size must be === 3

      (listRev.head eq create0) must be(true)
      (listRev.tail.head eq create1) must be(true)
      (listRev.tail.tail.head eq create2) must be(true)
      (listRev.tail.tail.tail.head eq null) must be(true)

      (create0.next eq create1) must be(true)
      (create1.next eq create2) must be(true)
      (create2.next eq null) must be(true)
    }

  }

  "EarliestFirstSystemMessageList" must {

    "properly prepend reversed message lists to the front" in {
      val create0 = Create(0)
      val create1 = Create(1)
      val create2 = Create(2)
      val create3 = Create(3)
      val create4 = Create(4)
      val create5 = Create(5)

      val fwdList = create3 :: create4 :: create5 :: ENil
      val revList = create2 :: create1 :: create0 :: LNil

      val list = revList reverse_::: fwdList

      (list.head eq create0) must be(true)
      (list.tail.head eq create1) must be(true)
      (list.tail.tail.head eq create2) must be(true)
      (list.tail.tail.tail.head eq create3) must be(true)
      (list.tail.tail.tail.tail.head eq create4) must be(true)
      (list.tail.tail.tail.tail.tail.head eq create5) must be(true)
      (list.tail.tail.tail.tail.tail.tail.head eq null) must be(true)

      (LNil reverse_::: ENil) == ENil must be(true)
      ((create0 :: LNil reverse_::: ENil).head eq create0) must be(true)
      ((LNil reverse_::: create0 :: ENil).head eq create0) must be(true)
    }

  }
}
