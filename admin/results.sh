#!/bin/bash

# This file is part of ballot_box.
# Copyright (C) 2014-2016  Agora Voting SL <agora@agoravoting.com>

# ballot_box is free software: you can redistribute it and/or modify
# it under the terms of the GNU Affero General Public License as published by
# the Free Software Foundation, either version 3 of the License.

# ballot_box  is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU Affero General Public License for more details.

# You should have received a copy of the GNU Affero General Public License
# along with ballot_box.  If not, see <http://www.gnu.org/licenses/>.

# ./results.sh -t tally.tar.gz -c config.json -s

# fixes errors on non-ascii characters
export LC_ALL=en_US.UTF-8
export LANG=en_US.UTF-8
export LANGUAGE=en_US.UTF-8

AGORA_RESULTS=/tmp/agora-results
VENV=/root/.virtualenvs
source $VENV/agora-results/bin/activate
$AGORA_RESULTS/agora-results $*
