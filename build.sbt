name := """agora-elections"""

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.1"

libraryDependencies ++= Seq(
  jdbc,
  cache,
  ws,
  "com.typesafe.play" %% "play-slick" % "0.8.0",
  "org.postgresql" % "postgresql" % "9.3-1100-jdbc4",
  "org.bouncycastle" % "bcprov-jdk16" % "1.45",
  "com.googlecode.owasp-java-html-sanitizer" % "owasp-java-html-sanitizer" % "r239",
  "commons-validator" % "commons-validator" % "1.4.1",
  "com.github.mumoshu" %% "play2-memcached-play23" % "0.7.0"
)

resolvers += "Spy Repository" at "http://files.couchbase.com/maven2" // required to resolve `spymemcached`, the plugin's dependency.