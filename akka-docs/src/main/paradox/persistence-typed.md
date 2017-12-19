# Persistence 

TODO

## Dependency

sbt
:   @@@vars
    ```
    "com.typesafe.akka" %% "akka-persistence-typed" % "$akka.version$"
    ```
    @@@

Gradle
:   @@@vars
    ```
    dependencies {
      compile group: 'com.typesafe.akka', name: 'akka-persistence-typed_2.11', version: '$akka.version$'
    }
    ```
    @@@

Maven
:   @@@vars
    ```
    <dependency>
      <groupId>com.typesafe.akka</groupId>
      <artifactId>akka-persistence-typed_$scala.binary_version$</artifactId>
      <version>$akka.version$</version>
    </dependency>
    ```
    @@@
