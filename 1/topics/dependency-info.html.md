---
date_published: 2026-05-27
date_modified: 2026-05-27
canonical_url: https://ike.network/hl7-ig-corpus-example/topics/dependency-info.html
---

# Maven Coordinates

## [Apache Maven](#apache-maven)

```
<dependency>
  <groupId>network.ike.examples</groupId>
  <artifactId>topics</artifactId>
  <version>1-SNAPSHOT</version>
  <type>pom</type>
</dependency>
```

## [Apache Ivy](#apache-ivy)

```
<dependency org="network.ike.examples" name="topics" rev="1-SNAPSHOT">
  <artifact name="topics" type="pom" />
</dependency>
```

## [Groovy Grape](#groovy-grape)

```
@Grapes(
@Grab(group='network.ike.examples', module='topics', version='1-SNAPSHOT')
)
```

## [Gradle/Grails](#gradle-grails)

```
implementation 'network.ike.examples:topics:1-SNAPSHOT'
```

## [Scala SBT](#scala-sbt)

```
libraryDependencies += "network.ike.examples" % "topics" % "1-SNAPSHOT"
```

## [Leiningen](#leiningen)

```
[network.ike.examples/topics "1-SNAPSHOT"]
```
