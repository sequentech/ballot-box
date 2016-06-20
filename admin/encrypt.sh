#!/bin/sh

# This file is part of agora_elections.
# Copyright (C) 2014  Agora Voting SL <agora@agoravoting.com>

# agora_elections is free software: you can redistribute it and/or modify
# it under the terms of the GNU Affero General Public License as published by
# the Free Software Foundation, either version 3 of the License.

# agora_elections  is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU Affero General Public License for more details.

# You should have received a copy of the GNU Affero General Public License
# along with agora_elections.  If not, see <http://www.gnu.org/licenses/>.

IVY=/root/.ivy2/cache
AGORA_HOME=..
java -classpath  $AGORA_HOME/target/scala-2.11/agora-elections_2.11-1.0-SNAPSHOT.jar:$IVY/com.typesafe.play/play-json_2.11/jars/play-json_2.11-2.3.6.jar:$IVY/org.scala-lang/scala-library/jars/scala-library-2.11.1.jar:$IVY/com.fasterxml.jackson.core/jackson-core/bundles/jackson-core-2.3.3.jar:$IVY/com.fasterxml.jackson.core/jackson-databind/bundles/jackson-databind-2.3.3.jar:$IVY/com.fasterxml.jackson.core/jackson-annotations/bundles/jackson-annotations-2.3.2.jar:$IVY/com.typesafe.play/play-functional_2.11/jars/play-functional_2.11-2.3.6.jar:$IVY/org.joda/joda-convert/jars/joda-convert-1.6.jar:$IVY/joda-time/joda-time/jars/joda-time-2.3.jar commands.DemoVotes $*