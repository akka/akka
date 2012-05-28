seq(lsSettings:_*)

(description in LsKeys.lsync) := "Akka is the platform for the next generation of event-driven, scalable and fault-tolerant architectures on the JVM."

(homepage in LsKeys.lsync) := Some(url("http://akka.io"))

(LsKeys.tags in LsKeys.lsync) := Seq("actors", "stm", "concurrency", "distributed", "fault-tolerance", "scala", "java", "futures", "dataflow", "remoting")

(LsKeys.docsUrl in LsKeys.lsync) := Some(url("http://akka.io/docs"))

(licenses in LsKeys.lsync) := Seq(("Apache 2", url("http://www.apache.org/licenses/LICENSE-2.0.html")))

(externalResolvers in LsKeys.lsync) := Seq("Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases")
