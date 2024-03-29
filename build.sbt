// This file is part of ballot_box.
// Copyright (C) 2014-2016  Sequent Tech Inc <legal@sequentech.io>

// ballot_box is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License.

// ballot_box  is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU Affero General Public License for more details.

// You should have received a copy of the GNU Affero General Public License
// along with ballot_box.  If not, see <http://www.gnu.org/licenses/>.

name := """ballot-box"""

version := "master"

fork in run := true

fork in Test := true

javaOptions in Test += "-Dconfig.file=conf/application.test.conf"

trapExit in run := false

lazy val root = (project in file(".")).enablePlugins(PlayScala)

licenses += ("AGPL-3.0", url("https://www.gnu.org/licenses/agpl-3.0.en.html"))

startYear := Some(2014)

homepage := Some(url("https://github.com/sequentech/ballot-box"))

organizationName := "Sequent Tech Inc"

organizationHomepage := Some(url("https://sequentech.io"))

scalaVersion := "2.11.6"

libraryDependencies ++= Seq(
  jdbc,
  cache,
  ws,
  "com.typesafe.play" %% "play-slick" % "0.8.0",
  "org.postgresql" % "postgresql" % "42.7.2",
  "org.bouncycastle" % "bcprov-jdk15to18" % "1.76",
  "com.googlecode.owasp-java-html-sanitizer" % "owasp-java-html-sanitizer" % "20211018.2",
  "commons-validator" % "commons-validator" % "1.4.1",
  "com.github.mumoshu" %% "play2-memcached-play23" % "0.7.0",
  "com.typesafe.play" %% "play-json" % "2.4.1" excludeAll( ExclusionRule(organization = "com.typesafe.play") ),
  "org.cvogt" %% "play-json-extensions" % "0.3.0" excludeAll( ExclusionRule(organization = "com.typesafe.play") )
)

// add this if can't resolve akka-slf4j_2.11
// resolvers += "mvn" at "https://repo1.maven.org/maven2/"
resolvers += "Spy Repository" at "https://files.couchbase.com/maven2" // required to resolve `spymemcached`, the plugin's dependency.

resolvers += "Typesafe repository" at "https://repo.typesafe.com/typesafe/releases/"
