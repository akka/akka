/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.dispatch.sysmsg

import akka.testkit.AkkaSpec

class SystemMessageListSpec extends AkkaSpec {
  import SystemMessageList.LNil
  import SystemMessageList.ENil

  "The SystemMessageList value class" must {

    "handle empty lists correctly" in {
      LNil.head should be(null)
      LNil.isEmpty should be(true)
      (LNil.reverse == ENil) should be(true)
    }

    "able to append messages" in {
      val create0 = Failed(null, null, 0)
      val create1 = Failed(null, null, 1)
      val create2 = Failed(null, null, 2)
      ((create0 :: LNil).head eq create0) should be(true)
      ((create1 :: create0 :: LNil).head eq create1) should be(true)
      ((create2 :: create1 :: create0 :: LNil).head eq create2) should be(true)

      (create2.next eq create1) should be(true)
      (create1.next eq create0) should be(true)
      (create0.next eq null) should be(true)
    }

    "able to deconstruct head and tail" in {
      val create0 = Failed(null, null, 0)
      val create1 = Failed(null, null, 1)
      val create2 = Failed(null, null, 2)
      val list = create2 :: create1 :: create0 :: LNil

      (list.head eq create2) should be(true)
      (list.tail.head eq create1) should be(true)
      (list.tail.tail.head eq create0) should be(true)
      (list.tail.tail.tail.head eq null) should be(true)
    }

    "properly report size and emptyness" in {
      val create0 = Failed(null, null, 0)
      val create1 = Failed(null, null, 1)
      val create2 = Failed(null, null, 2)
      val list = create2 :: create1 :: create0 :: LNil

      list.size should be(3)
      list.isEmpty should be(false)

      list.tail.size should be(2)
      list.tail.isEmpty should be(false)

      list.tail.tail.size should be(1)
      list.tail.tail.isEmpty should be(false)

      list.tail.tail.tail.size should be(0)
      list.tail.tail.tail.isEmpty should be(true)

    }

    "properly reverse contents" in {
      val create0 = Failed(null, null, 0)
      val create1 = Failed(null, null, 1)
      val create2 = Failed(null, null, 2)
      val list = create2 :: create1 :: create0 :: LNil
      val listRev: EarliestFirstSystemMessageList = list.reverse

      listRev.isEmpty should be(false)
      listRev.size should be(3)

      (listRev.head eq create0) should be(true)
      (listRev.tail.head eq create1) should be(true)
      (listRev.tail.tail.head eq create2) should be(true)
      (listRev.tail.tail.tail.head eq null) should be(true)

      (create0.next eq create1) should be(true)
      (create1.next eq create2) should be(true)
      (create2.next eq null) should be(true)
    }

  }

  "EarliestFirstSystemMessageList" must {

    "properly prepend reversed message lists to the front" in {
      val create0 = Failed(null, null, 0)
      val create1 = Failed(null, null, 1)
      val create2 = Failed(null, null, 2)
      val create3 = Failed(null, null, 3)
      val create4 = Failed(null, null, 4)
      val create5 = Failed(null, null, 5)

      val fwdList = create3 :: create4 :: create5 :: ENil
      val revList = create2 :: create1 :: create0 :: LNil

      val list = revList reverse_::: fwdList

      (list.head eq create0) should be(true)
      (list.tail.head eq create1) should be(true)
      (list.tail.tail.head eq create2) should be(true)
      (list.tail.tail.tail.head eq create3) should be(true)
      (list.tail.tail.tail.tail.head eq create4) should be(true)
      (list.tail.tail.tail.tail.tail.head eq create5) should be(true)
      (list.tail.tail.tail.tail.tail.tail.head eq null) should be(true)

      (LNil reverse_::: ENil) == ENil should be(true)
      ((create0 :: LNil reverse_::: ENil).head eq create0) should be(true)
      ((LNil reverse_::: create0 :: ENil).head eq create0) should be(true)
    }

  }
}
