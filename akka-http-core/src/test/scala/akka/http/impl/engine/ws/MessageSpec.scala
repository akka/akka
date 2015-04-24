/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.engine.ws

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.util.Random
import org.scalatest.{ Matchers, FreeSpec }
import akka.stream.FlowShape
import akka.stream.scaladsl._
import akka.stream.testkit._
import akka.util.ByteString
import akka.http.scaladsl.model.ws._
import Protocol.Opcode

class MessageSpec extends FreeSpec with Matchers with WithMaterializerSpec {
  "The Websocket implementation should" - {
    "collect messages from frames" - {
      "for binary messages" - {
        "for an empty message" in new ClientTestSetup {
          val input = frameHeader(Opcode.Binary, 0, fin = true)

          pushInput(input)
          expectMessage(BinaryMessage.Strict(ByteString.empty))
        }
        "for one complete, strict, single frame message" in new ClientTestSetup {
          val data = ByteString("abcdef", "ASCII")
          val input = frameHeader(Opcode.Binary, 6, fin = true) ++ data

          pushInput(input)
          expectMessage(BinaryMessage.Strict(data))
        }
        "for a partial frame" in new ClientTestSetup {
          val data1 = ByteString("abc", "ASCII")
          val header = frameHeader(Opcode.Binary, 6, fin = true)

          pushInput(header ++ data1)
          val BinaryMessage.Streamed(dataSource) = expectMessage()
          val sub = TestSubscriber.manualProbe[ByteString]
          dataSource.runWith(Sink(sub))
          val s = sub.expectSubscription()
          s.request(2)
          sub.expectNext(data1)
        }
        "for a frame split up into parts" in new ClientTestSetup {
          val data1 = ByteString("abc", "ASCII")
          val header = frameHeader(Opcode.Binary, 6, fin = true)

          pushInput(header)
          val BinaryMessage.Streamed(dataSource) = expectMessage()
          val sub = TestSubscriber.manualProbe[ByteString]
          dataSource.runWith(Sink(sub))
          val s = sub.expectSubscription()
          s.request(2)
          pushInput(data1)
          sub.expectNext(data1)

          val data2 = ByteString("def", "ASCII")
          pushInput(data2)
          sub.expectNext(data2)
          sub.expectComplete()
        }

        "for a message split into several frames" in new ClientTestSetup {
          val data1 = ByteString("abc", "ASCII")
          val header1 = frameHeader(Opcode.Binary, 3, fin = false)

          pushInput(header1 ++ data1)
          val BinaryMessage.Streamed(dataSource) = expectMessage()
          val sub = TestSubscriber.manualProbe[ByteString]
          dataSource.runWith(Sink(sub))
          val s = sub.expectSubscription()
          s.request(2)
          sub.expectNext(data1)

          val header2 = frameHeader(Opcode.Continuation, 4, fin = true)
          val data2 = ByteString("defg", "ASCII")
          pushInput(header2 ++ data2)
          sub.expectNext(data2)
          sub.expectComplete()
        }
        "for several messages" in new ClientTestSetup {
          val data1 = ByteString("abc", "ASCII")
          val header1 = frameHeader(Opcode.Binary, 3, fin = false)

          pushInput(header1 ++ data1)
          val BinaryMessage.Streamed(dataSource) = expectMessage()
          val sub = TestSubscriber.manualProbe[ByteString]
          dataSource.runWith(Sink(sub))
          val s = sub.expectSubscription()
          s.request(2)
          sub.expectNext(data1)

          val header2 = frameHeader(Opcode.Continuation, 4, fin = true)
          val header3 = frameHeader(Opcode.Binary, 2, fin = true)
          val data2 = ByteString("defg", "ASCII")
          val data3 = ByteString("h")
          pushInput(header2 ++ data2 ++ header3 ++ data3)
          sub.expectNext(data2)
          sub.expectComplete()

          val BinaryMessage.Streamed(dataSource2) = expectMessage()
          val sub2 = TestSubscriber.manualProbe[ByteString]
          dataSource2.runWith(Sink(sub2))
          val s2 = sub2.expectSubscription()
          s2.request(2)
          sub2.expectNext(data3)

          val data4 = ByteString("i")
          pushInput(data4)
          sub2.expectNext(data4)
          sub2.expectComplete()
        }
        "unmask masked input on the server side" in new ServerTestSetup {
          val mask = Random.nextInt()
          val (data, _) = maskedASCII("abcdef", mask)
          val data1 = data.take(3)
          val data2 = data.drop(3)
          val header = frameHeader(Opcode.Binary, 6, fin = true, mask = Some(mask))

          pushInput(header ++ data1)
          val BinaryMessage.Streamed(dataSource) = expectMessage()
          val sub = TestSubscriber.manualProbe[ByteString]
          dataSource.runWith(Sink(sub))
          val s = sub.expectSubscription()
          s.request(2)
          sub.expectNext(ByteString("abc", "ASCII"))

          pushInput(data2)
          sub.expectNext(ByteString("def", "ASCII"))
          sub.expectComplete()
        }
      }
      "for text messages" - {
        "empty message" in new ClientTestSetup {
          val input = frameHeader(Opcode.Text, 0, fin = true)

          pushInput(input)
          expectMessage(TextMessage.Strict(""))
        }
        "decode complete, strict frame from utf8" in new ClientTestSetup {
          val msg = "äbcdef€\uffff"
          val data = ByteString(msg, "UTF-8")
          val input = frameHeader(Opcode.Text, data.size, fin = true) ++ data

          pushInput(input)
          expectMessage(TextMessage.Strict(msg))
        }
        "decode utf8 as far as possible for partial frame" in new ClientTestSetup {
          val msg = "bäcdef€"
          val data = ByteString(msg, "UTF-8")
          val data0 = data.slice(0, 2)
          val data1 = data.slice(2, 5)
          val data2 = data.slice(5, data.size)
          val input = frameHeader(Opcode.Text, data.size, fin = true) ++ data0

          pushInput(input)
          val TextMessage.Streamed(parts) = expectMessage()
          val sub = TestSubscriber.manualProbe[String]
          parts.runWith(Sink(sub))
          val s = sub.expectSubscription()
          s.request(4)
          sub.expectNext("b")

          pushInput(data1)
          sub.expectNext("äcd")
        }
        "decode utf8 with code point split across frames" in new ClientTestSetup {
          val msg = "äbcdef€"
          val data = ByteString(msg, "UTF-8")
          val data0 = data.slice(0, 1)
          val data1 = data.slice(1, data.size)
          val header0 = frameHeader(Opcode.Text, data0.size, fin = false)

          pushInput(header0 ++ data0)
          val TextMessage.Streamed(parts) = expectMessage()
          val sub = TestSubscriber.manualProbe[String]
          parts.runWith(Sink(sub))
          val s = sub.expectSubscription()
          s.request(4)
          sub.expectNoMsg(100.millis)

          val header1 = frameHeader(Opcode.Continuation, data1.size, fin = true)
          pushInput(header1 ++ data1)
          sub.expectNext("äbcdef€")
        }
        "unmask masked input on the server side" in new ServerTestSetup {
          val mask = Random.nextInt()
          val (data, _) = maskedUTF8("äbcdef€", mask)
          val data1 = data.take(3)
          val data2 = data.drop(3)
          val header = frameHeader(Opcode.Binary, data.size, fin = true, mask = Some(mask))

          pushInput(header ++ data1)
          val BinaryMessage.Streamed(dataSource) = expectMessage()
          val sub = TestSubscriber.manualProbe[ByteString]
          dataSource.runWith(Sink(sub))
          val s = sub.expectSubscription()
          s.request(2)
          sub.expectNext(ByteString("äb", "UTF-8"))

          pushInput(data2)
          sub.expectNext(ByteString("cdef€", "UTF-8"))
          sub.expectComplete()
        }
      }
    }
    "render frames from messages" - {
      "for binary messages" - {
        "for a short strict message" in new ServerTestSetup {
          val data = ByteString("abcdef", "ASCII")
          val msg = BinaryMessage.Strict(data)
          netOutSub.request(5)
          pushMessage(msg)

          expectFrameOnNetwork(Opcode.Binary, data, fin = true)
        }
        "for a strict message larger than configured maximum frame size" in pending
        "for a streamed message" in new ServerTestSetup {
          val data = ByteString("abcdefg", "ASCII")
          val pub = TestPublisher.manualProbe[ByteString]
          val msg = BinaryMessage.Streamed(Source(pub))
          netOutSub.request(6)
          pushMessage(msg)
          val sub = pub.expectSubscription()

          expectFrameHeaderOnNetwork(Opcode.Binary, 0, fin = false)

          val data1 = data.take(3)
          val data2 = data.drop(3)

          sub.sendNext(data1)
          expectFrameOnNetwork(Opcode.Continuation, data1, fin = false)

          sub.sendNext(data2)
          expectFrameOnNetwork(Opcode.Continuation, data2, fin = false)

          sub.sendComplete()
          expectFrameOnNetwork(Opcode.Continuation, ByteString.empty, fin = true)
        }
        "for a streamed message with a chunk being larger than configured maximum frame size" in pending
        "and mask input on the client side" in new ClientTestSetup {
          val data = ByteString("abcdefg", "ASCII")
          val pub = TestPublisher.manualProbe[ByteString]
          val msg = BinaryMessage.Streamed(Source(pub))
          netOutSub.request(7)
          pushMessage(msg)
          val sub = pub.expectSubscription()

          expectFrameHeaderOnNetwork(Opcode.Binary, 0, fin = false)

          val data1 = data.take(3)
          val data2 = data.drop(3)

          sub.sendNext(data1)
          expectMaskedFrameOnNetwork(Opcode.Continuation, data1, fin = false)

          sub.sendNext(data2)
          expectMaskedFrameOnNetwork(Opcode.Continuation, data2, fin = false)

          sub.sendComplete()
          expectFrameOnNetwork(Opcode.Continuation, ByteString.empty, fin = true)
        }
      }
      "for text messages" - {
        "for a short strict message" in new ServerTestSetup {
          val text = "äbcdef"
          val msg = TextMessage.Strict(text)
          netOutSub.request(5)
          pushMessage(msg)

          expectFrameOnNetwork(Opcode.Text, ByteString(text, "UTF-8"), fin = true)
        }
        "for a strict message larger than configured maximum frame size" in pending
        "for a streamed message" in new ServerTestSetup {
          val text = "äbcd€fg"
          val pub = TestPublisher.manualProbe[String]
          val msg = TextMessage.Streamed(Source(pub))
          netOutSub.request(6)
          pushMessage(msg)
          val sub = pub.expectSubscription()

          expectFrameHeaderOnNetwork(Opcode.Text, 0, fin = false)

          val text1 = text.take(3)
          val text1Bytes = ByteString(text1, "UTF-8")
          val text2 = text.drop(3)
          val text2Bytes = ByteString(text2, "UTF-8")

          sub.sendNext(text1)
          expectFrameOnNetwork(Opcode.Continuation, text1Bytes, fin = false)

          sub.sendNext(text2)
          expectFrameOnNetwork(Opcode.Continuation, text2Bytes, fin = false)

          sub.sendComplete()
          expectFrameOnNetwork(Opcode.Continuation, ByteString.empty, fin = true)
        }
        "for a streamed message don't convert half surrogate pairs naively" in new ServerTestSetup {
          val gclef = "𝄞"
          gclef.size shouldEqual 2

          // split up the code point
          val half1 = gclef.take(1)
          val half2 = gclef.drop(1)
          println(half1(0).toInt.toHexString)
          println(half2(0).toInt.toHexString)

          val pub = TestPublisher.manualProbe[String]
          val msg = TextMessage.Streamed(Source(pub))
          netOutSub.request(6)

          pushMessage(msg)
          val sub = pub.expectSubscription()

          expectFrameHeaderOnNetwork(Opcode.Text, 0, fin = false)
          sub.sendNext(half1)

          expectNoNetworkData()
          sub.sendNext(half2)
          expectFrameOnNetwork(Opcode.Continuation, ByteString(gclef, "utf8"), fin = false)
        }
        "for a streamed message with a chunk being larger than configured maximum frame size" in pending
        "and mask input on the client side" in new ClientTestSetup {
          val text = "abcdefg"
          val pub = TestPublisher.manualProbe[String]
          val msg = TextMessage.Streamed(Source(pub))
          netOutSub.request(5)
          pushMessage(msg)
          val sub = pub.expectSubscription()

          expectFrameOnNetwork(Opcode.Text, ByteString.empty, fin = false)

          val text1 = text.take(3)
          val text1Bytes = ByteString(text1, "UTF-8")
          val text2 = text.drop(3)
          val text2Bytes = ByteString(text2, "UTF-8")

          sub.sendNext(text1)
          expectMaskedFrameOnNetwork(Opcode.Continuation, text1Bytes, fin = false)

          sub.sendNext(text2)
          expectMaskedFrameOnNetwork(Opcode.Continuation, text2Bytes, fin = false)

          sub.sendComplete()
          expectFrameOnNetwork(Opcode.Continuation, ByteString.empty, fin = true)
        }
      }
    }
    "supply automatic low-level websocket behavior" - {
      "respond to ping frames unmasking them on the server side" in new ServerTestSetup {
        val mask = Random.nextInt()
        val input = frameHeader(Opcode.Ping, 6, fin = true, mask = Some(mask)) ++ maskedASCII("abcdef", mask)._1

        pushInput(input)
        netOutSub.request(5)
        expectFrameOnNetwork(Opcode.Pong, ByteString("abcdef"), fin = true)
      }
      "respond to ping frames masking them on the client side" in new ClientTestSetup {
        val input = frameHeader(Opcode.Ping, 6, fin = true) ++ ByteString("abcdef")

        pushInput(input)
        netOutSub.request(5)
        expectMaskedFrameOnNetwork(Opcode.Pong, ByteString("abcdef"), fin = true)
      }
      "respond to ping frames interleaved with data frames (without mixing frame data)" in new ServerTestSetup {
        // receive multi-frame message
        // receive and handle interleaved ping frame
        // concurrently send out messages from handler
        val mask1 = Random.nextInt()
        val input1 = frameHeader(Opcode.Binary, 3, fin = false, mask = Some(mask1)) ++ maskedASCII("123", mask1)._1
        pushInput(input1)

        val BinaryMessage.Streamed(dataSource) = expectMessage()
        val sub = TestSubscriber.manualProbe[ByteString]
        dataSource.runWith(Sink(sub))
        val s = sub.expectSubscription()
        s.request(2)
        sub.expectNext(ByteString("123", "ASCII"))

        val outPub = TestPublisher.manualProbe[ByteString]
        val msg = BinaryMessage.Streamed(Source(outPub))
        netOutSub.request(10)
        pushMessage(msg)

        expectFrameHeaderOnNetwork(Opcode.Binary, 0, fin = false)

        val outSub = outPub.expectSubscription()
        val outData1 = ByteString("abc", "ASCII")
        outSub.sendNext(outData1)
        expectFrameOnNetwork(Opcode.Continuation, outData1, fin = false)

        val pingMask = Random.nextInt()
        val pingData = maskedASCII("pling", pingMask)._1
        val pingData0 = pingData.take(3)
        val pingData1 = pingData.drop(3)
        pushInput(frameHeader(Opcode.Ping, 5, fin = true, mask = Some(pingMask)) ++ pingData0)
        expectNoNetworkData()
        pushInput(pingData1)
        expectFrameOnNetwork(Opcode.Pong, ByteString("pling", "ASCII"), fin = true)

        val outData2 = ByteString("def", "ASCII")
        outSub.sendNext(outData2)
        expectFrameOnNetwork(Opcode.Continuation, outData2, fin = false)

        outSub.sendComplete()
        expectFrameOnNetwork(Opcode.Continuation, ByteString.empty, fin = true)

        val mask2 = Random.nextInt()
        val input2 = frameHeader(Opcode.Continuation, 3, fin = true, mask = Some(mask2)) ++ maskedASCII("456", mask2)._1
        pushInput(input2)
        sub.expectNext(ByteString("456", "ASCII"))
        sub.expectComplete()
      }
      "don't respond to unsolicited pong frames" in new ClientTestSetup {
        val data = frameHeader(Opcode.Pong, 6, fin = true) ++ ByteString("abcdef")
        pushInput(data)
        netOutSub.request(5)
        expectNoNetworkData()
      }
    }
    "provide close behavior" - {
      "after receiving regular close frame when idle (user closes immediately)" in new ServerTestSetup {
        netInSub.expectRequest()
        netOutSub.request(20)
        messageOutSub.request(20)

        pushInput(closeFrame(Protocol.CloseCodes.Regular, mask = true))
        messageIn.expectComplete()

        netIn.expectNoMsg(100.millis) // especially the cancellation not yet
        expectNoNetworkData()
        messageOutSub.sendComplete()

        expectCloseCodeOnNetwork(Protocol.CloseCodes.Regular)
        netOut.expectComplete()
        netInSub.expectCancellation()
      }
      "after receiving close frame without close code" in new ServerTestSetup {
        netInSub.expectRequest()
        pushInput(frameHeader(Opcode.Close, 0, fin = true))
        messageIn.expectComplete()

        messageOutSub.sendComplete()
        // especially mustn't be Procotol.CloseCodes.NoCodePresent
        expectCloseCodeOnNetwork(Protocol.CloseCodes.Regular)
        netOut.expectComplete()
        netInSub.expectCancellation()
      }
      "after receiving regular close frame when idle (user still sends some data)" in new ServerTestSetup {
        netOutSub.request(20)
        messageOutSub.request(20)

        pushInput(closeFrame(Protocol.CloseCodes.Regular, mask = true))
        messageIn.expectComplete()

        // sending another message is allowed before closing (inherently racy)
        val pub = TestPublisher.manualProbe[ByteString]
        val msg = BinaryMessage.Streamed(Source(pub))
        pushMessage(msg)
        expectFrameOnNetwork(Opcode.Binary, ByteString.empty, fin = false)

        val data = ByteString("abc", "ASCII")
        val dataSub = pub.expectSubscription()
        dataSub.sendNext(data)
        expectFrameOnNetwork(Opcode.Continuation, data, fin = false)

        dataSub.sendComplete()
        expectFrameOnNetwork(Opcode.Continuation, ByteString.empty, fin = true)

        messageOutSub.sendComplete()
        expectCloseCodeOnNetwork(Protocol.CloseCodes.Regular)
        netOut.expectComplete()
      }
      "after receiving regular close frame when fragmented message is still open" in {
        new ServerTestSetup {
          netOutSub.request(10)
          messageInSub.request(10)

          pushInput(frameHeader(Protocol.Opcode.Binary, 0, fin = false))
          val BinaryMessage.Streamed(dataSource) = messageIn.expectNext()
          val inSubscriber = TestSubscriber.manualProbe[ByteString]
          dataSource.runWith(Sink(inSubscriber))
          val inSub = inSubscriber.expectSubscription()

          val outData = ByteString("def", "ASCII")
          val mask = Random.nextInt()
          pushInput(frameHeader(Protocol.Opcode.Continuation, 3, fin = false, mask = Some(mask)) ++ maskedBytes(outData, mask)._1)
          inSub.request(5)
          inSubscriber.expectNext(outData)

          pushInput(closeFrame(Protocol.CloseCodes.Regular, mask = true))

          // This is arguable: we could also just fail the subStream but complete the main message stream regularly.
          // However, truncating an ongoing message by closing without sending a `Continuation(fin = true)` first
          // could be seen as something being amiss.
          messageIn.expectError()
          inSubscriber.expectError()
          // truncation of open message

          // sending another message is allowed before closing (inherently racy)

          val pub = TestPublisher.manualProbe[ByteString]
          val msg = BinaryMessage.Streamed(Source(pub))
          pushMessage(msg)
          expectFrameOnNetwork(Opcode.Binary, ByteString.empty, fin = false)

          val data = ByteString("abc", "ASCII")
          val dataSub = pub.expectSubscription()
          dataSub.sendNext(data)
          expectFrameOnNetwork(Opcode.Continuation, data, fin = false)

          dataSub.sendComplete()
          expectFrameOnNetwork(Opcode.Continuation, ByteString.empty, fin = true)

          messageOutSub.sendComplete()
          expectCloseCodeOnNetwork(Protocol.CloseCodes.Regular)
          netOut.expectComplete()
        }
      }
      "after receiving error close frame" in pending
      "after peer closes connection without sending a close frame" in new ServerTestSetup {
        netInSub.expectRequest()
        netInSub.sendComplete()

        messageIn.expectComplete()
        messageOutSub.sendComplete()

        expectCloseCodeOnNetwork(Protocol.CloseCodes.Regular)
        netOut.expectComplete()
      }
      "when user handler closes (simple)" in new ServerTestSetup {
        messageOutSub.sendComplete()
        expectCloseCodeOnNetwork(Protocol.CloseCodes.Regular)

        netOut.expectNoMsg(100.millis) // wait for peer to close regularly
        pushInput(closeFrame(Protocol.CloseCodes.Regular, mask = true))

        messageIn.expectComplete()
        netOut.expectComplete()
        netInSub.expectCancellation()
      }
      "when user handler closes main stream and substream only afterwards" in new ServerTestSetup {
        netOutSub.request(10)
        messageInSub.request(10)

        // send half a message
        val pub = TestPublisher.manualProbe[ByteString]
        val msg = BinaryMessage.Streamed(Source(pub))
        pushMessage(msg)
        expectFrameOnNetwork(Opcode.Binary, ByteString.empty, fin = false)

        val data = ByteString("abc", "ASCII")
        val dataSub = pub.expectSubscription()
        dataSub.sendNext(data)
        expectFrameOnNetwork(Opcode.Continuation, data, fin = false)

        messageOutSub.sendComplete()
        expectNoNetworkData() // need to wait for substream to close

        dataSub.sendComplete()
        expectFrameOnNetwork(Opcode.Continuation, ByteString.empty, fin = true)
        expectCloseCodeOnNetwork(Protocol.CloseCodes.Regular)
        netOut.expectNoMsg(100.millis) // wait for peer to close regularly

        val mask = Random.nextInt()
        pushInput(closeFrame(Protocol.CloseCodes.Regular, mask = true))

        messageIn.expectComplete()
        netOut.expectComplete()
        netInSub.expectCancellation()
      }
      "if user handler fails" in pending
      "if peer closes with invalid close frame" - {
        "close code outside of the valid range" in new ServerTestSetup {
          netInSub.expectRequest()
          pushInput(frameHeader(Opcode.Close, 1, mask = Some(Random.nextInt()), fin = true) ++ ByteString("x"))

          val error = messageIn.expectError()

          expectCloseCodeOnNetwork(Protocol.CloseCodes.ProtocolError)
          netOut.expectComplete()
          netInSub.expectCancellation()
        }
        "close data of size 1" in new ServerTestSetup {
          netInSub.expectRequest()
          pushInput(frameHeader(Opcode.Close, 1, mask = Some(Random.nextInt()), fin = true) ++ ByteString("x"))

          val error = messageIn.expectError()

          expectCloseCodeOnNetwork(Protocol.CloseCodes.ProtocolError)
          netOut.expectComplete()
          netInSub.expectCancellation()
        }
        "reason is no valid utf8 data" in pending
      }
      "timeout if user handler closes and peer doesn't send a close frame" in new ServerTestSetup {
        override protected def closeTimeout: FiniteDuration = 100.millis

        netInSub.expectRequest()
        messageOutSub.sendComplete()
        expectCloseCodeOnNetwork(Protocol.CloseCodes.Regular)

        netOut.expectComplete()
        netInSub.expectCancellation()
      }
      "timeout after we close after error and peer doesn't send a close frame" in new ServerTestSetup {
        override protected def closeTimeout: FiniteDuration = 100.millis

        netInSub.expectRequest()

        pushInput(frameHeader(Opcode.Binary, 0, fin = true, rsv1 = true))
        expectProtocolErrorOnNetwork()
        messageOutSub.sendComplete()

        netOut.expectComplete()
        netInSub.expectCancellation()
      }
      "ignore frames peer sends after close frame" in new ServerTestSetup {
        netInSub.expectRequest()
        pushInput(closeFrame(Protocol.CloseCodes.Regular, mask = true))

        messageIn.expectComplete()

        pushInput(frameHeader(Opcode.Binary, 0, fin = true))
        messageOutSub.sendComplete()
        expectCloseCodeOnNetwork(Protocol.CloseCodes.Regular)

        netOut.expectComplete()
        netInSub.expectCancellation()
      }
    }
    "reject unexpected frames" - {
      "reserved bits set" - {
        "rsv1" in new ServerTestSetup {
          pushInput(frameHeader(Opcode.Binary, 0, fin = true, rsv1 = true))
          expectProtocolErrorOnNetwork()
        }
        "rsv2" in new ServerTestSetup {
          pushInput(frameHeader(Opcode.Binary, 0, fin = true, rsv2 = true))
          expectProtocolErrorOnNetwork()
        }
        "rsv3" in new ServerTestSetup {
          pushInput(frameHeader(Opcode.Binary, 0, fin = true, rsv3 = true))
          expectProtocolErrorOnNetwork()
        }
      }
      "highest bit of 64-bit length is set" in new ServerTestSetup {
        import BitBuilder._

        val header =
          b"""0000          # flags
                  xxxx=1    # opcode
              1             # mask?
               xxxxxxx=7f   # length
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx=ffffffff
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx
              xxxxxxxx=ffffffff # length64
              00000000
              00000000
              00000000
              00000000 # empty mask
          """

        pushInput(header)
        expectProtocolErrorOnNetwork()
      }
      "control frame bigger than 125 bytes" in new ServerTestSetup {
        pushInput(frameHeader(Opcode.Ping, 126, fin = true, mask = Some(0)))
        expectProtocolErrorOnNetwork()
      }
      "fragmented control frame" in new ServerTestSetup {
        pushInput(frameHeader(Opcode.Ping, 0, fin = false, mask = Some(0)))
        expectProtocolErrorOnNetwork()
      }
      "unexpected continuation frame" in new ServerTestSetup {
        pushInput(frameHeader(Opcode.Continuation, 0, fin = false, mask = Some(0)))
        expectProtocolErrorOnNetwork()
      }
      "unexpected data frame when waiting for continuation" in new ServerTestSetup {
        pushInput(frameHeader(Opcode.Binary, 0, fin = false) ++
          frameHeader(Opcode.Binary, 0, fin = false))
        expectProtocolErrorOnNetwork()
      }
      "invalid utf8 encoding for single frame message" in new ClientTestSetup {
        val data = ByteString(
          (128 + 64).toByte, // start two byte sequence
          0 // but don't finish it
          )

        pushInput(frameHeader(Opcode.Text, 2, fin = true) ++ data)
        expectCloseCodeOnNetwork(Protocol.CloseCodes.InconsistentData)
      }
      "invalid utf8 encoding for streamed frame" in new ClientTestSetup {
        val data = ByteString(
          (128 + 64).toByte, // start two byte sequence
          0 // but don't finish it
          )

        pushInput(frameHeader(Opcode.Text, 0, fin = false) ++
          frameHeader(Opcode.Continuation, 2, fin = true) ++
          data)
        expectCloseCodeOnNetwork(Protocol.CloseCodes.InconsistentData)
      }
      "truncated utf8 encoding for single frame message" in new ClientTestSetup {
        val data = ByteString("€", "UTF-8").take(1) // half a euro
        pushInput(frameHeader(Opcode.Text, 1, fin = true) ++ data)
        expectCloseCodeOnNetwork(Protocol.CloseCodes.InconsistentData)
      }
      "truncated utf8 encoding for streamed frame" in new ClientTestSetup {
        val data = ByteString("€", "UTF-8").take(1) // half a euro
        pushInput(frameHeader(Opcode.Text, 0, fin = false) ++
          frameHeader(Opcode.Continuation, 1, fin = true) ++
          data)
        expectCloseCodeOnNetwork(Protocol.CloseCodes.InconsistentData)
      }
      "half a surrogate pair in utf8 encoding for a strict frame" in new ClientTestSetup {
        val data = ByteString(0xed, 0xa0, 0x80) // not strictly supported by utf-8
        pushInput(frameHeader(Opcode.Text, 3, fin = true) ++ data)
        expectCloseCodeOnNetwork(Protocol.CloseCodes.InconsistentData)
      }
      "half a surrogate pair in utf8 encoding for a streamed frame" in new ClientTestSetup {
        val data = ByteString(0xed, 0xa0, 0x80) // not strictly supported by utf-8
        pushInput(frameHeader(Opcode.Text, 0, fin = false))
        pushInput(frameHeader(Opcode.Continuation, 3, fin = true) ++ data)

        messageIn.expectError()

        expectCloseCodeOnNetwork(Protocol.CloseCodes.InconsistentData)
      }
      "unmasked input on the server side" in new ServerTestSetup {
        val data = ByteString("abcdef", "ASCII")
        val input = frameHeader(Opcode.Binary, 6, fin = true) ++ data

        pushInput(input)
        expectProtocolErrorOnNetwork()
      }
      "masked input on the client side" in new ClientTestSetup {
        val mask = Random.nextInt()
        val input = frameHeader(Opcode.Binary, 6, fin = true, mask = Some(mask)) ++ maskedASCII("abcdef", mask)._1

        pushInput(input)
        expectProtocolErrorOnNetwork()
      }
    }
    "support per-message-compression extension" in pending
  }

  class ServerTestSetup extends TestSetup {
    protected def serverSide: Boolean = true
  }
  class ClientTestSetup extends TestSetup {
    protected def serverSide: Boolean = false
  }
  abstract class TestSetup {
    protected def serverSide: Boolean
    protected def closeTimeout: FiniteDuration = 1.second

    val netIn = TestPublisher.manualProbe[ByteString]
    val netOut = TestSubscriber.manualProbe[ByteString]

    val messageIn = TestSubscriber.manualProbe[Message]
    val messageOut = TestPublisher.manualProbe[Message]

    val messageHandler: Flow[Message, Message, Unit] =
      Flow.wrap {
        FlowGraph.partial() { implicit b ⇒
          val in = b.add(Sink(messageIn))
          val out = b.add(Source(messageOut))

          FlowShape[Message, Message](in, out)
        }
      }

    Source(netIn)
      .via(printEvent("netIn"))
      .transform(() ⇒ new FrameEventParser)
      .via(Websocket.handleMessages(messageHandler, serverSide, closeTimeout = closeTimeout))
      .via(printEvent("frameRendererIn"))
      .transform(() ⇒ new FrameEventRenderer)
      .via(printEvent("frameRendererOut"))
      .to(Sink(netOut))
      .run()

    val netInSub = netIn.expectSubscription()
    val netOutSub = netOut.expectSubscription()
    val messageOutSub = messageOut.expectSubscription()
    val messageInSub = messageIn.expectSubscription()

    def pushInput(data: ByteString): Unit = {
      // TODO: expect/handle request?
      netInSub.sendNext(data)
    }
    def pushMessage(msg: Message): Unit = {
      messageOutSub.sendNext(msg)
    }

    def expectMessage(message: Message): Unit = {
      messageInSub.request(1)
      messageIn.expectNext(message)
    }
    def expectMessage(): Message = {
      messageInSub.request(1)
      messageIn.expectNext()
    }

    var inBuffer = ByteString.empty
    @tailrec final def expectNetworkData(bytes: Int): ByteString =
      if (inBuffer.size >= bytes) {
        val res = inBuffer.take(bytes)
        inBuffer = inBuffer.drop(bytes)
        res
      } else {
        netOutSub.request(1)
        inBuffer ++= netOut.expectNext()
        expectNetworkData(bytes)
      }

    def expectNetworkData(data: ByteString): Unit =
      expectNetworkData(data.size) shouldEqual data

    def expectFrameOnNetwork(opcode: Opcode, data: ByteString, fin: Boolean): Unit = {
      expectFrameHeaderOnNetwork(opcode, data.size, fin)
      expectNetworkData(data)
    }
    def expectMaskedFrameOnNetwork(opcode: Opcode, data: ByteString, fin: Boolean): Unit = {
      val Some(mask) = expectFrameHeaderOnNetwork(opcode, data.size, fin)
      val masked = maskedBytes(data, mask)._1
      expectNetworkData(masked)
    }

    /** Returns the mask if any is available */
    def expectFrameHeaderOnNetwork(opcode: Opcode, length: Long, fin: Boolean): Option[Int] = {
      val (op, l, f, m) = expectFrameHeaderOnNetwork()
      op shouldEqual opcode
      l shouldEqual length
      f shouldEqual fin
      m
    }
    def expectFrameHeaderOnNetwork(): (Opcode, Long, Boolean, Option[Int]) = {
      val header = expectNetworkData(2)

      val fin = (header(0) & Protocol.FIN_MASK) != 0
      val op = header(0) & Protocol.OP_MASK

      val hasMask = (header(1) & Protocol.MASK_MASK) != 0
      val length7 = header(1) & Protocol.LENGTH_MASK
      val length = length7 match {
        case 126 ⇒
          val length16Bytes = expectNetworkData(2)
          (length16Bytes(0) & 0xff) << 8 | (length16Bytes(1) & 0xff) << 0
        case 127 ⇒
          val length64Bytes = expectNetworkData(8)
          (length64Bytes(0) & 0xff).toLong << 56 |
            (length64Bytes(1) & 0xff).toLong << 48 |
            (length64Bytes(2) & 0xff).toLong << 40 |
            (length64Bytes(3) & 0xff).toLong << 32 |
            (length64Bytes(4) & 0xff).toLong << 24 |
            (length64Bytes(5) & 0xff).toLong << 16 |
            (length64Bytes(6) & 0xff).toLong << 8 |
            (length64Bytes(7) & 0xff).toLong << 0
        case x ⇒ x
      }
      val mask =
        if (hasMask) {
          val maskBytes = expectNetworkData(4)
          val mask =
            (maskBytes(0) & 0xff) << 24 |
              (maskBytes(1) & 0xff) << 16 |
              (maskBytes(2) & 0xff) << 8 |
              (maskBytes(3) & 0xff) << 0
          Some(mask)
        } else None

      (Opcode.forCode(op.toByte), length, fin, mask)
    }

    def expectProtocolErrorOnNetwork(): Unit = expectCloseCodeOnNetwork(Protocol.CloseCodes.ProtocolError)
    def expectCloseCodeOnNetwork(expectedCode: Int): Unit = {
      val (opcode, length, true, mask) = expectFrameHeaderOnNetwork()
      opcode shouldEqual Opcode.Close
      length should be >= 2.toLong

      val rawData = expectNetworkData(length.toInt)
      val data = mask match {
        case Some(m) ⇒ FrameEventParser.mask(rawData, m)._1
        case None    ⇒ rawData
      }

      val code = ((data(0) & 0xff) << 8) | ((data(1) & 0xff) << 0)
      code shouldEqual expectedCode
    }

    def expectNoNetworkData(): Unit =
      netOut.expectNoMsg(100.millis)
  }

  def frameHeader(
    opcode: Opcode,
    length: Long,
    fin: Boolean,
    mask: Option[Int] = None,
    rsv1: Boolean = false,
    rsv2: Boolean = false,
    rsv3: Boolean = false): ByteString = {
    def set(should: Boolean, mask: Int): Int =
      if (should) mask else 0

    val flags =
      set(fin, Protocol.FIN_MASK) |
        set(rsv1, Protocol.RSV1_MASK) |
        set(rsv2, Protocol.RSV2_MASK) |
        set(rsv3, Protocol.RSV3_MASK)

    val opcodeByte = opcode.code | flags

    require(length >= 0)
    val (lengthByteComponent, lengthBytes) =
      if (length < 126) (length.toByte, ByteString.empty)
      else if (length < 65536) (126.toByte, shortBE(length.toInt))
      else throw new IllegalArgumentException("Only lengths < 65536 allowed in test")

    val maskMask = if (mask.isDefined) Protocol.MASK_MASK else 0
    val maskBytes = mask match {
      case Some(mask) ⇒ intBE(mask)
      case None       ⇒ ByteString.empty
    }
    val lengthByte = lengthByteComponent | maskMask
    ByteString(opcodeByte.toByte, lengthByte.toByte) ++ lengthBytes ++ maskBytes
  }
  def closeFrame(closeCode: Int, mask: Boolean): ByteString =
    if (mask) {
      val mask = Random.nextInt()
      frameHeader(Opcode.Close, 2, fin = true, mask = Some(mask)) ++
        maskedBytes(shortBE(closeCode), mask)._1
    } else
      frameHeader(Opcode.Close, 2, fin = true) ++
        shortBE(closeCode)

  def maskedASCII(str: String, mask: Int): (ByteString, Int) =
    FrameEventParser.mask(ByteString(str, "ASCII"), mask)
  def maskedUTF8(str: String, mask: Int): (ByteString, Int) =
    FrameEventParser.mask(ByteString(str, "UTF-8"), mask)
  def maskedBytes(bytes: ByteString, mask: Int): (ByteString, Int) =
    FrameEventParser.mask(bytes, mask)

  def shortBE(value: Int): ByteString = {
    require(value >= 0 && value < 65536, s"Value wasn't in short range: $value")
    ByteString(
      ((value >> 8) & 0xff).toByte,
      ((value >> 0) & 0xff).toByte)
  }
  def intBE(value: Int): ByteString =
    ByteString(
      ((value >> 24) & 0xff).toByte,
      ((value >> 16) & 0xff).toByte,
      ((value >> 8) & 0xff).toByte,
      ((value >> 0) & 0xff).toByte)

  val trace = false // set to `true` for debugging purposes
  def printEvent[T](marker: String): Flow[T, T, Unit] =
    if (trace) akka.http.impl.util.printEvent(marker)
    else Flow[T]
}
