# Testing 

@@@ warning

This module is currently marked as @ref:[may change](common/may-change.md) in the sense
  of being the subject of active research. This means that API or semantics can
  change without warning or deprecation period and it is not recommended to use
  this module in production just yet—you have been warned.
  
  
@@@

To use the testkit add the following dependency:
## Dependency

sbt
:   @@@vars
    ```
    "com.typesafe.akka" %% "akka-testkit-typed" % "$akka.version$"
    ```
    @@@

Gradle
:   @@@vars
    ```
    dependencies {
      compile group: 'com.typesafe.akka', name: 'akka-testkit-typed_2.11', version: '$akka.version$'
    }
    ```
    @@@

Maven
:   @@@vars
    ```
    <dependency>
      <groupId>com.typesafe.akka</groupId>
      <artifactId>akka-testkit-typed_$scala.binary_version$</artifactId>
      <version>$akka.version$</version>
    </dependency>
    ```
    @@@
    
TODO