#!/usr/bin/env python3

# This file is part of ballot_box.
# Copyright (C) 2022  Sequent Tech Inc <legal@sequentech.io>

# ballot_box is free software: you can redistribute it and/or modify
# it under the terms of the GNU Affero General Public License as published by
# the Free Software Foundation, either version 3 of the License.

# ballot_box  is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU Affero General Public License for more details.

# You should have received a copy of the GNU Affero General Public License
# along with ballot_box.  If not, see <http://www.gnu.org/licenses/>.

import argparse
import json
from ..admin import (
    get_iam_db_connection,
    get_db_connection,
    get_votes_table,
    get_elections_table
)

def dump_election_config(election_id, election_config_path):
    '''
    Dumps the election config of the specified election in the specified
    election config path and returning the election's segmentation category
    name.
    '''
    pass

def get_categorized_voters_path(election_id):
    '''
    Returns a temporal file path containing in CSV format the eligible voter
    list of an election, with two columns: the segmentation category of the
    voter and the voter id. Sorted by voter id descending.
    '''
    pass

def get_ballots_with_voters_path(election_id):
    '''
    Returns a temporal file path containing in CSV format the list of cast
    ballots along with their voter ids. Sorted by voter id descending.
    '''
    pass

def dump_categorized_votes(
    election_id,
    categorized_voters_path,
    ballots_with_voters_path,
    output_path
):
    '''
    Performs a join between the CSV files with categorized voters and ballots
    with voters using the voter-id as the join key, and dumping the resulting
    CSV file containing cast ballots with their category in the specified output
    path.
    '''
    pass

def main():
    parser = argparse.ArgumentParser(
        description=(
            'dump votes with two columns in CSV format: category and ' +
            'encrypted ballot'
        )
    )
    parser.add_argument(
        '--output-ballots-path',
        required=True,
        help=(
            'Path where to write the output ballots. Output will be in CSV ' +
            'format with two columns, with no header line, and the first ' +
            'column being the category name and the second column being the ' +
            'encrypted ballot.'
        )
    )
    parser.add_argument(
        '--election-id',
        type=int,
        required=True,
        help=(
            'Id of the election to dump'
        )
    )
    parser.add_argument(
        '--election-config-path',
        required=True,
        help=(
            'File path where the election config should be written to'
        )
    )
    args = parser.parse_args()
    election_id = args.election_id
    election_config_path = args.election_config_path
    output_ballots_path = args.output_ballots_path

    segmentation_category_name = dump_election_config(
        election_id,
        election_config_path
    )
    categorized_voters_path = get_categorized_voters_path(
        election_id,
        segmentation_category_name
    )
    ballots_with_voters_path = get_ballots_with_voters_path(election_id)
    dump_categorized_votes(
        election_id,
        categorized_voters_path,
        ballots_with_voters_path,
        output_ballots_path
    )

if __name__ == "__main__":
    main()
