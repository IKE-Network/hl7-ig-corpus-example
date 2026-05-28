---
date_published: 2026-05-27
date_modified: 2026-05-27
canonical_url: https://ike.network/hl7-ig-corpus-example/corpus-guide/dependency-info.html
---

# Maven Coordinates

## [Apache Maven](#apache-maven)

```
<dependency>
  <groupId>network.ike.examples</groupId>
  <artifactId>corpus-guide</artifactId>
  <version>1-SNAPSHOT</version>
  <type>pom</type>
</dependency>
```

## [Apache Ivy](#apache-ivy)

```
<dependency org="network.ike.examples" name="corpus-guide" rev="1-SNAPSHOT">
  <artifact name="corpus-guide" type="pom" />
</dependency>
```

## [Groovy Grape](#groovy-grape)

```
@Grapes(
@Grab(group='network.ike.examples', module='corpus-guide', version='1-SNAPSHOT')
)
```

## [Gradle/Grails](#gradle-grails)

```
implementation 'network.ike.examples:corpus-guide:1-SNAPSHOT'
```

## [Scala SBT](#scala-sbt)

```
libraryDependencies += "network.ike.examples" % "corpus-guide" % "1-SNAPSHOT"
```

## [Leiningen](#leiningen)

```
[network.ike.examples/corpus-guide "1-SNAPSHOT"]
```
