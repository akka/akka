Java Serialization, Fixed in Akka 2.4.17
========================================

Date
----

10 Feburary 2017

Description of Vulnerability
~~~~~~~~~~~~~~~~~~~~~~~~~~~~

An attacker that can connect to an ``ActorSystem`` exposed via Akka Remote over TCP can gain remote code execution 
capabilities in the context of the JVM process that runs the ActorSystem if:

* ``JavaSerializer`` is enabled (default in Akka 2.4.x)
* and TLS is disabled *or* TLS is enabled with ``akka.remote.netty.ssl.security.require-mutual-authentication = false``
  (which is still the default in Akka 2.4.x)
* regardless of whether ``untrusted`` mode is enabled or not

Java deserialization is `known to be vulnerable <https://community.hpe.com/t5/Security-Research/The-perils-of-Java-deserialization/ba-p/6838995>`_ to attacks when attacker can provide arbitrary types.

Akka Remoting uses Java serialiser as default configuration which makes it vulnerable in its default form. The documentation of how to disable Java serializer was not complete. The documentation of how to enable mutual authentication was missing (only described in reference.conf).

To protect against such attacks the system should be updated to Akka `2.4.17` or later and be configured with 
:ref:`disabled Java serializer <disable-java-serializer-scala>`. Additional protection can be achieved when running in an 
untrusted network by enabling :ref:`TLS with mutual authentication <remote-tls-scala>`.

Please subscribe to the `akka-security <https://groups.google.com/forum/#!forum/akka-security>`_ mailing list to be notified promptly about future security issues.

Severity
~~~~~~~~

The `CVSS <https://en.wikipedia.org/wiki/CVSS>`_ score of this vulnerability is 3.6 (Low), based on vector `AV:A/AC:H/Au:N/C:P/I:P/A:P/E:F/RL:OF/RC:C <https://nvd.nist.gov/cvss.cfm?calculator&version=2&vector=%28AV:A/AC:H/Au:N/C:P/I:P/A:P/E:F/RL:OF/RC:C%29>`_.

Rationale for the score:

* AV:A - Best practice is that Akka remoting nodes should only be accessible from the adjacent network, so in good setups, this will be adjacent.
* AC:H - In order to exploit, you first need to be able to connect to the Akka system.  This will usually mean exploiting some other system that connects to it first.
* C:P, I:P, A:P - Partial impact for each of confidentiality, integrity and availability, due to the already high impact to these that being able to connect to a remote actor system in the first place has.

Affected Versions
~~~~~~~~~~~~~~~~~

- Akka `2.4.16` and prior
- Akka `2.5-M1` (milestone not intended for production)

Fixed Versions
~~~~~~~~~~~~~~

We have prepared patches for the affected versions, and have released the following versions which resolve the issue: 

- Akka `2.4.17` (Scala 2.11, 2.12)

Binary and source compatibility has been maintained for the patched releases so the upgrade procedure is as simple as changing the library dependency.

It will also be fixed in 2.5-M2 or 2.5.0-RC1.

Acknowledgements
~~~~~~~~~~~~~~~~

We would like to thank Alvaro Munoz & Adrian Bravo for their thorough investigation and bringing this issue to our attention.