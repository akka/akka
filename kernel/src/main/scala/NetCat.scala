package org.apache.mina.example.scala.netcat

import _root_.scala.actors.Actor
import _root_.scala.actors.Actor._
import _root_.scala.actors.Exit
import _root_.scala.collection.immutable

import java.net.InetSocketAddress
import java.nio.charset.Charset

import org.apache.mina.common._
import org.apache.mina.filter.codec.ProtocolCodecFilter
import org.apache.mina.filter.codec.textline.TextLineCodecFactory
import org.apache.mina.integration.scala.common._
import org.apache.mina.integration.scala.common.IoHandlerEvent._
import org.apache.mina.integration.scala.common.IoServiceEvent._
import org.apache.mina.integration.scala.common.IoSessionCall._
import org.apache.mina.integration.scala.common.IoSessionConfigOption._
import org.apache.mina.integration.scala.util._
import org.apache.mina.integration.scala.util.CallableActor._
import org.apache.mina.transport.socket.nio.NioSocketConnector

/**
 * (<b>Entry point</b>) NetCat client.  NetCat client connects to the specified
 * endpoint and prints out received data.  NetCat client disconnects
 * automatically when no data is read for 10 seconds.
 *
 * @author The Apache MINA Project (dev@mina.apache.org)
 * @version $Rev:$
 */
object NetCat {

 def handleSession(session: Actor) = {
   loop {
     react {
       case Opened => {
         // Set reader idle time to 10 seconds.
         // sessionIdle(...) method will be invoked when no data is read
         // for 10 seconds.
         val config = immutable.Map.empty[Any, Any] +
Tuple2(IdleTime(IdleStatus.READER_IDLE), 10)
         session.callReact(SetConfig(config)) {
           case OK(_) => ()
           case Error(cause) => exit(('setConfigFailed, cause))
         }
       }
       case Closed => {
         // Print out total number of bytes read from the remote peer.
         session.callReact(GetReadBytes) {
           case OK(readBytes) => {
             System.err.println
             System.err.println("Total " + readBytes + " byte(s)");
             exit()
           }
           case Error(cause) => exit(('getReadBytesFailed, cause))
         }
       }
       case Idle(status) => {
         // Close the connection if reader is idle.
         if (status == IdleStatus.READER_IDLE) {
           session.callReact(CloseOnFlush) {
             case OK(_) => exit()
             case Error(cause) => exit(('idleCloseFailed, cause))
           }
         }
       }
       case MessageReceived(buf: IoBuffer) => {
         // Print out read buffer content.
         while (buf.hasRemaining) {
           System.out.print(buf.get.asInstanceOf[char])
         }
         System.out.flush()
       }
       // Consume other IoHandlerEvents, or exit if something goes wrong.
       case ExceptionCaught(cause) => exit(('exceptionCaught, cause))
       case _: IoHandlerEvent => () // Consume
       case unexpected => exit(('unexpectedMessage, unexpected))
     }
   }
 }

 def main(args: Array[String]) {
   var host = System.getProperty("netcat.host")
   var portString = System.getProperty("netcat.port")
   if ((host eq null) || (portString eq null)) {
     // Read from command line
     if (args.length != 2) {
       System.out.println(this.getClass().getName() + " <hostname> <port>")
       return
     }
     host = args(0)
     portString = args(1)
   }
   val port = Integer.parseInt(portString)

   // Create TCP/IP connector.
   val connector = new NioSocketConnector()
   connector.setConnectTimeout(30)

   // Hook up our code, and start service.
   val handlingReference = IoSessionActor.installHandling(connector,
handleSession(_))
   val cf = connector.connect(new InetSocketAddress(host, port))
   cf.awaitUninterruptibly()
   cf.getSession().getCloseFuture().awaitUninterruptibly()
   handlingReference.removeHandling
   connector.dispose()
 }
}
