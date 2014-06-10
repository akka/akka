Low-Level Akka-Http API Design
==============================

This is an internal design document for facilitating discussions.
Polished descriptions are therefore not expected.


Basic HTTP Message Model
------------------------

This is the basic message model which models HTTP message entities as a ``Producer[ByteString]``:

.. code:: scala

   abstract class HttpMessage {
     def headers: List[HttpHeader]
     def entity: HttpEntity
     def protocol: HttpProtocol
     // ...
   }

   final case class HttpRequest(method: HttpMethod,
                          uri: Uri,
                          headers: List[HttpHeader],
                          entity: HttpEntity,
                          protocol: HttpProtocol) extends HttpMessage {
     // ...
   }

   final case class HttpResponse(status: StatusCode,
                           headers: List[HttpHeader],
                           entity: HttpEntity,
                           protocol: HttpProtocol) extends HttpMessage {
     // ...
   }

   trait HttpEntity {
     def isEmpty: Boolean
     def data: Producer[ByteString]
     // ...
   }


Client-side
-----------

Just like we currently do in *spray-can* the entry-point to the low-level API is the ``HttpManager`` actor which you
get a hold of via the ``IO(Http)`` extension.

On the client-side akka-http offers 3 different API-levels (mirroring what we currently have in *spray-can*):

1. Low-level connection-based API

   Here the user has access to a single HTTP connection and fully controls what requests are to be sent when.
   This is how you establish a client-side connection:

   .. code:: scala

      val connection: Future[OutgoingHttpConnection] =
        IO(Http) ? Http.Connect(remoteAddress: InetSocketAddress /*, ... options */)

      final case class OutgoingHttpConnection(remoteAddress: InetSocketAddress,
                                        localAddress: InetSocketAddress,
                                        untypedProcessor: HttpClientProcessor[Any]) extends OutgoingHttpChannel {
        def processor[T] = untypedProcessor.asInstanceOf[HttpClientProcessor[T]]
      }

      trait OutgoingHttpChannel {
        def processor[T]: HttpClientProcessor[T]
      }

      trait HttpClientProcessor[T] extends Processor[(HttpRequest, T), (HttpResponse, T)]


   If the connection cannot be established the ``HttpManager`` responds with ``Status.Failure`` rather than
   an ``OutgoingHttpConnection`` instance.


2. Host-level API (unchanged from what *spray-can* does today)

   When requested with an ``Http.HostConnectorSetup`` command the ``HttpManager`` can set up a host-specific connection
   pool that allows for communication with a specific host without having to worry about connection mgmt.:

   .. code:: scala

      val infoFuture: Future[HostConnectorInfo] =
        IO(Http) ? Http.HostConnectorSetup(host, port /*, ... options */)

      final case class HostConnectorInfo(hostConnector: ActorRef, setup: HostConnectorSetup)

      for {
        info <- infoFuture
        response: HttpResponse <- info.hostConnector ? HttpRequest(...)
      } ...

   We don't want to switch this API to a stream-based one because we want to receive responses as soon as they
   have come in, which is not necessarily the order in which the requests were sent (after all, we are on top of a
   connection pool here).


3. Request-level API (unchanged from what *spray-can* does today)

   The ``HttpManager`` will automatically set up a new host-connector or re-use an existing one if you send it an
   ``HttpRequest`` directly:

   .. code:: scala

      val response: Future[HttpResponse] = IO(Http) ? HttpRequest(...)

   As in the case of the host-level API a stream-based API is not as good as the message-based API here, because
   responses generally don't come in in the order that they were sent.


Even though the host- and request-level APIs should remain message-based underneath it probably *does* make sense to
add a stream-based API on top, so you can say something like this:

.. code:: scala

   val hostChannel: Future[HttpHostChannel] =
     IO(Http) ? Http.HostChannelSetup(host, port /*, ... options */)

   final case class HttpHostChannel(host: String, port: Int,
                              untypedProcessor: HttpClientProcessor[Any]) extends OutgoingHttpChannel {
     def processor[T] = untypedProcessor.asInstanceOf[HttpClientProcessor[T]]
   }

   val requestChannel: Future[HttpRequestChannel] =
     IO(Http) ? Http.RequestChannelSetup(/*, ... options */)

   final case class HttpRequestChannel(untypedProcessor: HttpClientProcessor[Any]) extends OutgoingHttpChannel {
     def processor[T] = untypedProcessor.asInstanceOf[HttpClientProcessor[T]]
   }


Server-side
-----------

This is how you set up an HTTP server:

.. code:: scala

   val binding: Future[HttpServerBinding] =
     IO(Http) ? Http.Bind(endpoint: InetSocketAddress /*, ... options */)

   final case class HttpServerBinding(localAddress: InetSocketAddress,
                                connectionStream: Producer[IncomingHttpConnection]) {
     def handleWith(f: HttpRequest => Future[HttpResponse]): Unit =
       connectionStream.foreach(_ handle f)
   }

   final case class IncomingHttpConnection(remoteAddress: InetSocketAddress,
                                     requestStream: Producer[HttpRequest],
                                     responseStream: Consumer[HttpResponse]) {
     def handleWith(f: HttpRequest => Future[HttpResponse]): Unit =
       requestStream.map(f).concat produceTo responseStream

     def handleWith(processor: Processor[HttpRequest, HttpResponse]): Unit = {
       processor.produceTo(responseStream)
       requestStream.produceTo(processor)
     }
   }

Unbinding is done by unsubscribing from ``binding.connectionStream`` or by sending an ``Http.Unbind`` command to the
sender of the ``HttpServerBinding`` response (which causes the ``connectionStream`` to be completed).
If HTTP pipelining is enabled in the config the ``requestStream`` might produce several requests before the application
has produced the first response to the ``responseStream``. In these cases the application itself has to make sure that
the responses are produced in the same order as the requests have come in.
If you stay within the stream infrastructure this should be not hard, but it is (of course) possible to mess this up.

The design of exposing a ``Producer[HttpRequest]`` / ``Consumer[HttpRequest]`` pair to the application has the benefit
of enabling easy and proper proxying, because the server- and client-side interfaces nicely plug into each other.
So it's possible to connect a server interface (almost) directly into a client-interface and construct a proxy which
maintains proper back-pressure across the whole chain!
The only thing that needs to be put in-between is a little logic which rewrites host-headers and so on, but apart from
this purely HTTP-related logic a single connected stream setup across several machines should be possible.


Underlying TCP Layer Requirements
---------------------------------

We propose the following (additional) TCP-level interfaces for akka-io:

Client-side API
~~~~~~~~~~~~~~~

The TCP and HTTP interfaces should be structurally very similar:

.. code:: scala

   val connection: Future[OutgoingTcpConnection] =
     IO(Tcp) ? Tcp.Connect(remoteAddress: InetSocketAddress /*, ... options */)

   final case class OutgoingTcpConnection(remoteAddress: InetSocketAddress,
                                    localAddress: InetSocketAddress,
                                    processor: TcpClientProcessor) {
     def outputStream: Consumer[ByteString] = processor
     def inputStream: Producer[ByteString] = processor
   }

   trait TcpClientProcessor extends Processor[ByteString, ByteString]
                                      
Completing the ``outputStream`` should result in a FIN being sent and therefore a half-close of the connection.
Unsubscribing from the ``inputstream`` should close the sockets reading side which might or might not result in a
real effect on the underlying connection. In the case of a normal TCP connection the peer will not receive any signal
from this but if the connection is encrypted the action of closing the reading side will result in actual data being
transmitted.
This means that a full connection close will entail doing both, completing the ``outputStream`` and unsubscribing from
the ``inputStream``.
A confirmed close would consist of an ``outputStream.onComplete()`` and the subsequent waiting for completion of
the ``inputStream`` (upon which one is implicitly unsubscribed from the ``inputStream`` and the connection therefore closed).
Aborting the connection (TCP Reset) is done by calling ``onError`` on the ``outputStream``.

Server-side API
~~~~~~~~~~~~~~~

Again the TCP and HTTP interfaces should be structurally very similar:

.. code:: scala

   val binding: Future[TcpServerBinding] =
     IO(Tcp) ? Tcp.Bind(endpoint: InetSocketAddress /*, ... options */)

   final case class TcpServerBinding(localAddress: InetSocketAddress,
                               connectionStream: Producer[IncomingTcpConnection])

    final case class IncomingTcpConnection(remoteAddress: InetSocketAddress,
                                     inputStream: Producer[ByteString],
                                     outputStream: Consumer[ByteString]) {
      def handleWith(processor: Processor[ByteString, ByteString]): Unit = {
        processor.produceTo(outputStream)
        inputStream.produceTo(processor)
      }
    }

As in the case of HTTP unbinding is done by unsubscribing from ``binding.connectionStream`` or by sending a
``Tcp.Unbind`` command to the sender of the ``TcpServerBinding`` response (which causes the ``connectionStream`` to be
completed).
Closing and aborting an ``IncomingTcpConnection`` is identical to the ``OutgoingTcpConnection``.


Other Requirements
~~~~~~~~~~~~~~~~~~

SSL
  We consider SSL support outside of the scope of akka-http!
  It should be part of akka-io.
  In order to enable user access to the encrypted data SSL/TLS support should be modelled as a stand-alone asynchronous
  component that can be plugged into an outgoing or incoming TCP pipeline:

  .. code:: scala

     class SslTlsCryptor {
       def plainTextInput: Producer[IncomingSslSession]
       def plainTextOutput: Consumer[OutgoingSslSession]
       def cypherTextInput: Consumer[ByteString]
       def cypherTextOutput: Producer[ByteString]
     }

     final case class IncomingSslSession(
       sessionInfo: SessionInfo,
       data: Producer[ByteString]
     )

     final case class OutgoingSslSession(
       negotiation: SessionNegotiation,
       data: Consumer[ByteString]
     )


Connection-level Idle-Timeout Checking
  Apart from SSL idle-timeout detection is the other (last) remaining thing in our old *spray-io* module that is
  independent of HTTP and therefore should go into akka-io. We should think about how to best model it in a general
  way without messing up our otherwise nice and clean interfaces.
