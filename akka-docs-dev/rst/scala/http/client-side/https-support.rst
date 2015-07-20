.. _clientSideHTTPS:

Client-Side HTTPS Support
=========================

Akka HTTP supports TLS encryption on the client-side as well as on the :ref:`server-side <serverSideHTTPS>`.

The central vehicle for configuring encryption is the ``HttpsContext``, which is defined as such:

.. includecode2:: /../../akka-http-core/src/main/scala/akka/http/scaladsl/Http.scala
   :snippet: https-context-impl

In addition to the ``outgoingConnection``, ``newHostConnectionPool`` and ``cachedHostConnectionPool`` methods the
`akka.http.scaladsl.Http`_ extension also defines ``outgoingConnectionTls``, ``newHostConnectionPoolTls`` and
``cachedHostConnectionPoolTls``. These methods work identically to their counterparts without the ``-Tls`` suffix,
with the exception that all connections will always be encrypted.

The ``singleRequest`` and ``superPool`` methods determine the encryption state via the scheme of the incoming request,
i.e. requests to an "https" URI will be encrypted, while requests to an "http" URI won't.

The encryption configuration for all HTTPS connections, i.e. the ``HttpsContext`` is determined according to the
following logic:

1. If the optional ``httpsContext`` method parameter is defined it contains the configuration to be used (and thus
   takes precedence over any potentially set default client-side ``HttpsContext``).

2. If the optional ``httpsContext`` method parameter is undefined (which is the default) the default client-side
   ``HttpsContext`` is used, which can be set via the ``setDefaultClientHttpsContext`` on the ``Http`` extension.

3. If no default client-side ``HttpsContext`` has been set via the ``setDefaultClientHttpsContext`` on the ``Http``
   extension the default system configuration is used.

Usually the process is, if the default system TLS configuration is not good enough for your application's needs,
that you configure a custom ``HttpsContext`` instance and set it via ``Http().setDefaultClientHttpsContext``.
Afterwards you simply use ``outgoingConnectionTls``, ``newHostConnectionPoolTls``, ``cachedHostConnectionPoolTls``,
``superPool`` or ``singleRequest`` without a specific ``httpsContext`` argument, which causes encrypted connections
to rely on the configured default client-side ``HttpsContext``.


.. _akka.http.scaladsl.Http: @github@/akka-http-core/src/main/scala/akka/http/scaladsl/Http.scala
