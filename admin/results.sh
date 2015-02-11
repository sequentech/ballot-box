#!/bin/bash
# ./results.sh -t tally.tar.gz -c config.json -s

# fixes errors on non-ascii characters
declare -x LANG="en_GB.UTF-8"
declare -x LANGUAGE="en_GB:en"

AGORA_RESULTS=/tmp/agora-results
VENV=/root/.virtualenvs
source $VENV/agora-results/bin/activate
$AGORA_RESULTS/agora-results $*
