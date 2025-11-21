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

// The Typesafe repository is deprecated, using Maven Central instead
resolvers += "Maven Central" at "https://repo1.maven.org/maven2/"

// Add Typesafe Ivy releases resolver
resolvers += Resolver.url("Typesafe Ivy Releases", url("https://repo.typesafe.com/typesafe/ivy-releases/"))(Resolver.ivyStylePatterns)

// Override transitive dependencies to use available versions from Maven Central
libraryDependencies ++= Seq(
  "com.typesafe" % "jse_2.10" % "1.2.4",
  "com.typesafe" % "npm_2.10" % "1.2.2",
  "com.typesafe" % "webdriver_2.10" % "1.1.1"
)

// The Play plugin
addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.3.9")
