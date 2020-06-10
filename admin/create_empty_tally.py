#!/usr/bin/env python

# This file is part of agora_elections.
# Copyright (C) 2014-2016  Agora Voting SL <agora@agoravoting.com>

# agora_elections is free software: you can redistribute it and/or modify
# it under the terms of the GNU Affero General Public License as published by
# the Free Software Foundation, either version 3 of the License.

# agora_elections  is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU Affero General Public License for more details.

# You should have received a copy of the GNU Affero General Public License
# along with agora_elections.  If not, see <http://www.gnu.org/licenses/>.

import argparse
import json
from uuid import uuid4
import os
import tarfile
import tempfile
import sys

def main(argv):
    parser = argparse.ArgumentParser(
        description='agora-elections create_empty_tally script'
    )
    
    parser.add_argument(
        '-c', 
        '--config', 
        help='file to the configuration of the election'
    )
    
    parser.add_argument(
        '-o', 
        '--output',
        help='output tally.tar.gz file path'
    )
    
    args = parser.parse_args()

    with open(args.config, 'r') as f:
        questions = json.loads(f.read())['questions']

    questions_f = tempfile.NamedTemporaryFile(delete=False)
    questions_f.write(
        json.dumps(
            questions,
            ensure_ascii=False,
            sort_keys=True,
            indent=4,
            separators=(',', ': ')
        ).encode('utf-8')
    )
    questions_f.close()

    tar = tarfile.open(args.output, mode="w|gz")
    tinfo = tar.gettarinfo(questions_f.name, "questions_json")
    with open(questions_f.name, 'rb') as questions_f2:
        tar.addfile(tinfo, questions_f2)

    empty_file = tempfile.NamedTemporaryFile(delete=False)
    empty_file.close()

    with open(empty_file.name, 'rb') as empty_file_f:
        for index, question in enumerate(questions):
            empty_file_tinfo = tar.gettarinfo(
                empty_file.name, 
                "%d-%s/plaintexts_json" % (index, uuid4())
            )
            tar.addfile(empty_file_tinfo, empty_file_f)

    tar.close()
    os.unlink(questions_f.name)
    os.unlink(empty_file.name)

if __name__ == "__main__":
	main(sys.argv[1:])
