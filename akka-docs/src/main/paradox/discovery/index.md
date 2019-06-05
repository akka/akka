# Discovery

Akka Discovery provides an interface around various ways of locating services. The built in methods are:

* Configuration
* DNS
* Aggregate

In addition [Akka Management](https://doc.akka.io/docs/akka-management/current/) contains methods such as:

* Kubernetes API
* AWS
* Consul
* Marathon API

Discovery used to be part of Akka Management but has become an Akka module as of `2.5.19` of Akka and version `1.0.0`
of Akka Management. If you're also using Akka Management for other service discovery methods or bootstrap make
sure you are using at least version `1.0.0` of Akka Management.

## Dependency

@@dependency[sbt,Gradle,Maven] {
  group="com.typesafe.akka"
  artifact="akka-discovery_$scala.binary_version$"
  version="$akka.version$"
}

## How it works

Loading the extension:

Scala
:  @@snip [CompileOnlySpec.scala](/akka-discovery/src/test/scala/doc/akka/discovery/CompileOnlySpec.scala) { #loading }

Java
:  @@snip [CompileOnlyTest.java](/akka-discovery/src/test/java/jdoc/akka/discovery/CompileOnlyTest.java) { #loading }

A `Lookup` contains a mandatory `serviceName` and an optional `portName` and `protocol`. How these are interpreted is discovery 
method dependent e.g.DNS does an A/AAAA record query if any of the fields are missing and an SRV query for a full look up:

Scala
:  @@snip [CompileOnlySpec.scala](/akka-discovery/src/test/scala/doc/akka/discovery/CompileOnlySpec.scala) { #basic }

Java
:  @@snip [CompileOnlyTest.java](/akka-discovery/src/test/java/jdoc/akka/discovery/CompileOnlyTest.java) { #basic }


`portName` and `protocol` are optional and their meaning is interpreted by the method.

Scala
:  @@snip [CompileOnlySpec.scala](/akka-discovery/src/test/scala/doc/akka/discovery/CompileOnlySpec.scala) { #full }

Java
:  @@snip [CompileOnlyTest.java](/akka-discovery/src/test/java/jdoc/akka/discovery/CompileOnlyTest.java) { #full }

Port can be used when a service opens multiple ports e.g. a HTTP port and an Akka remoting port.

## Discovery Method: DNS

DNS discovery maps `Lookup` queries as follows:

* `serviceName`, `portName` and `protocol` set: SRV query in the form: `_port._protocol.name` Where the `_`s are added.
* Any query  missing any of the fields is mapped to a A/AAAA query for the `serviceName`

The mapping between Akka service discovery terminology and SRV terminology:

* SRV service = port
* SRV name = serviceName
* SRV protocol = protocol

Configure `akka-dns` to be used as the discovery implementation in your `application.conf`:

@@snip[application.conf](/akka-discovery/src/test/scala/akka/discovery/dns/DnsDiscoverySpec.scala){ #configure-dns }

From there on, you can use the generic API that hides the fact which discovery method is being used by calling:

Scala
:   ```scala
    import akka.discovery.ServiceDiscovery
    val system = ActorSystem("Example")
    // ...
    val discovery = ServiceDiscovery(system).discovery
    val result: Future[Resolved] = discovery.lookup("service-name", resolveTimeout = 500 milliseconds)
    ```

Java
:   ```java
    import akka.discovery.ServiceDiscovery;
    ActorSystem system = ActorSystem.create("Example");
    // ...
    SimpleServiceDiscovery discovery = ServiceDiscovery.get(system).discovery();
    Future<SimpleServiceDiscovery.Resolved> result = discovery.lookup("service-name", Duration.create("500 millis"));
    ```

### How it works

DNS discovery will use either A/AAAA records or SRV records depending on whether a `Simple` or `Full` lookup is issued.
The advantage of SRV records is that they can include a port.

#### SRV records

Lookups with all the fields set become SRV queries. For example:

```
dig srv _service._tcp.akka.test

; <<>> DiG 9.11.3-RedHat-9.11.3-6.fc28 <<>> srv service.tcp.akka.test
;; global options: +cmd
;; Got answer:
;; ->>HEADER<<- opcode: QUERY, status: NOERROR, id: 60023
;; flags: qr aa rd ra; QUERY: 1, ANSWER: 2, AUTHORITY: 1, ADDITIONAL: 5

;; OPT PSEUDOSECTION:
; EDNS: version: 0, flags:; udp: 4096
; COOKIE: 5ab8dd4622e632f6190f54de5b28bb8fb1b930a5333c3862 (good)
;; QUESTION SECTION:
;service.tcp.akka.test.         IN      SRV

;; ANSWER SECTION:
_service._tcp.akka.test.  86400   IN      SRV     10 60 5060 a-single.akka.test.
_service._tcp.akka.test.  86400   IN      SRV     10 40 5070 a-double.akka.test.

```

In this case `service.tcp.akka.test` resolves to `a-single.akka.test` on port `5060`
and `a-double.akka.test` on port `5070`. Currently discovery does not support the weightings.

#### A/AAAA records

Lookups with any fields missing become A/AAAA record queries. For example:

```
dig a-double.akka.test

; <<>> DiG 9.11.3-RedHat-9.11.3-6.fc28 <<>> a-double.akka.test
;; global options: +cmd
;; Got answer:
;; ->>HEADER<<- opcode: QUERY, status: NOERROR, id: 11983
;; flags: qr aa rd ra; QUERY: 1, ANSWER: 2, AUTHORITY: 1, ADDITIONAL: 2

;; OPT PSEUDOSECTION:
; EDNS: version: 0, flags:; udp: 4096
; COOKIE: 16e9815d9ca2514d2f3879265b28bad05ff7b4a82721edd0 (good)
;; QUESTION SECTION:
;a-double.akka.test.            IN      A

;; ANSWER SECTION:
a-double.akka.test.     86400   IN      A       192.168.1.21
a-double.akka.test.     86400   IN      A       192.168.1.22

```

In this case `a-double.akka.test` would resolve to `192.168.1.21` and `192.168.1.22`.

## Discovery Method: Configuration

Configuration currently ignores all fields apart from service name.

For simple use cases configuration can be used for service discovery. The advantage of using Akka Discovery with
configuration rather than your own configuration values is that applications can be migrated to a more
sophisticated discovery method without any code changes.


Configure it to be used as discovery method in your `application.conf`

```
akka {
  discovery.method = config
}
```

By default the services discoverable are defined in `akka.discovery.config.services` and have the following format:

```
akka.discovery.config.services = {
  service1 = {
    endpoints = [
      {
        host = "cat"
        port = 1233
      },
      {
        host = "dog"
        port = 1234
      }
    ]
  },
  service2 = {
    endpoints = []
  }
}
```

Where the above block defines two services, `service1` and `service2`.
Each service can have multiple endpoints.

## Discovery Method: Aggregate multiple discovery methods

Aggregate discovery allows multiple discovery methods to be aggregated e.g. try and resolve
via DNS and fall back to configuration.

To use aggregate discovery add its dependency as well as all of the discovery that you
want to aggregate.

Configure `aggregate` as `akka.discovery.method` and which discovery methods are tried and in which order.

```
akka {
  discovery {
    method = aggregate
    aggregate {
      discovery-methods = ["akka-dns", "config"]
    }
    config {
      services {
        service1 {
          endpoints = [
            {
              host = "host1"
              port = 1233
            },
            {
              host = "host2"
              port = 1234
            }
          ]
        }
      }
    }
  }
}

```

The above configuration will result in `akka-dns` first being checked and if it fails or returns no
targets for the given service name then `config` is queried which i configured with one service called
`service1` which two hosts `host1` and `host2`.

## Migrating from Akka Management Discovery (before 1.0.0)

Akka Discovery started out as a submodule of Akka Management, before 1.0.0 of Akka Management. Akka Discovery is not compatible with those versions of Akka Management Discovery.

At least version `1.0.0` of any Akka Management module should be used if also using Akka Discovery.

Migration steps:

* Any custom discovery method should now implement `akka.discovery.ServiceDiscovery`
* `discovery-method` now has to be a configuration location under `akka.discovery` with at minimum a property `class` specifying the fully qualified name of the implementation of `akka.discovery.ServiceDiscovery`.
  Previous versions allowed this to be a class name or a fully qualified config location e.g. `akka.discovery.kubernetes-api` rather than just `kubernetes-api`



