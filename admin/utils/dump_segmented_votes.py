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
import csv
import copy

def is_quadratic_residue(value, p, q):
    return pow(value, q, p) == 1 

def get_encrypted_categories(question, category_names):
    '''
    Creates a dictionary with the key being the category name and the value a
    ciphertext encrypting the encoded category to be used later on.
    '''
    g = question['pub_keys']['g']
    y = question['pub_keys']['y']
    p = question['pub_keys']['p']
    q = question['pub_keys']['q']

    # the categories are encoded as 2^index
    encoded_category = 2
    encrypted_categories = dict()
    for category in category_names:
        while not is_quadratic_residue(encoded_category, p, q):
            encoded_category <<= 1
        assert encoded_category < q, f"too many categories ({len(category_names)})"
        encrypted_categories[category] = {
            "gr": g,
            "mhr": (encoded_category * y) % p
        }
        encoded_category <<= 1

    return encrypted_categories
    
def get_public_keys(pks_str):
    '''
    Given a string containing a json with the public keys, obtain the public
    keys in the desired format
    '''
    public_key_list = json.loads(pks_str)
    return [
        dict(
            g=int(pub_key["g"]),
            p=int(pub_key["p"]),
            q=int(pub_key["q"]),
            y=int(pub_key["y"])
        )
        for pub_key in public_key_list
    ]

def create_output_ballot(
    category_name,
    input_ballot,
    questions
):
    '''
    Create an output ballot, which will be the same as the input ballot except
    that it will contain:
    
    ballot_ciphertext = encrypted_ballot * encrypt(encoded_category)
    '''
    output_ballot = copy.deepcopy(input_ballot)
    output_ciphertexts = []
    for i in range(45000):
        for question_index, input_ciphertext in enumerate(input_ballot['choices']):
            question = questions[question_index]
            p = question['pub_keys']['p']

            encrypted_categories = question['encrypted_categories']
            encrypted_category = encrypted_categories[category_name]
            cat_gr = encrypted_category['gr']
            cat_mhr = encrypted_category['mhr']

            ballot_gr = int(input_ciphertext['alpha'])
            ballot_mhr = int(input_ciphertext['beta'])

            output_ciphertext = {
                "alpha": str((cat_gr * ballot_gr) % p),
                "beta": str((cat_mhr * ballot_mhr) % p)
            }
            output_ciphertexts.append(output_ciphertext)
    output_ballot['choices'] = output_ciphertexts
    return json.dumps(output_ballot)

def main():
    '''
    Main function of this script.

    It should be called in the following fashion:
    ```python
    python3 dump_segmented_votes.tsv \
        --input-ballots input-ballots.csv \
        --election-config election-config.json \
        --output-ballots segmented-ballots.json
    ```

    - Input ballots (`--input-ballots`) should be in CSV format with two columns,
      with no header line, and the first column being the category name and the
      second column being the encrypted ballot.
    - Election configuration (`--election-config`) should contain:
      1. The `pks` key with the list of public keys of each question in this
         election.
      2. The `configuration.questions` key with the list of questions and the 
         questions configuration including the list of answers (in the 
         `answers` sub-key).
      3. The `mixing-category-segmentation` key, containing a dict with a 
         `categories` key containing an ordered list of strings of the possible
         categories, and the key `category-name`, containg a string with the
         name of the extra_field related to the voters' category.
     - The output will be created in a new file in the path pointed with the
       `--output-ballots` parameter.
    
    The output will be a list of re-encrypted ballots. The ballots will be 
    reencrypted by creating the product of the encrypted ballot with the product
    of an encrypted ciphertext related to the category of that ballot.
    '''
    parser = argparse.ArgumentParser(
        description='dumps votes segmented by category'
    )
    parser.add_argument(
        '--input-ballots',
        required=True,
        help=(
            'Path to the file containing the input ballots. Should be in CSV ' +
            'format with two columns, with no header line, and the first ' +
            'column being the category name and the second column being the ' +
            'encrypted ballot.'
        )
    )
    parser.add_argument(
        '--election-config',
        required=True,
        help='Path to the file containing the election configuration.'
    )
    parser.add_argument(
        '--output-ballots',
        required=True,
        help='Path to the file where the output ballots should be created.'
    )
    args = parser.parse_args()
    
    # load input data
    election_config_path = args.election_config
    election_config = json.loads(open(election_config_path).read())
    pub_keys = get_public_keys(election_config['pks'])
    questions = election_config['configuration']['questions']

    # for simplicity assign each pub key to each question
    category_names = election_config['mixing-category-segmentation']["categories"]
    print(f"encrypting {len(category_names)} categories for {len(questions)} questions..")
    for i, question in enumerate(questions):
        question['pub_keys'] = pub_keys[i]
        question['encrypted_categories'] = get_encrypted_categories(
            question=question,
            category_names=category_names
        )
    print("..done")
    
    input_ballots_path = args.input_ballots
    input_ballots = csv.reader(open(input_ballots_path))
    output_ballots_path = args.output_ballots
    output_ballots_file = open(output_ballots_path, 'w')

    # for eack input ballot, obtain an output ballot and write it to the output
    # file
    for (category_name, input_ballot_str) in input_ballots:
        input_ballot = json.loads(input_ballot_str)
        output_ballots_file.write(
            create_output_ballot(
                category_name,
                input_ballot,
                questions
            )
            + '\n'
        )

if __name__ == "__main__":
    main()
