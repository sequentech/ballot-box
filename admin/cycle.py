#!/usr/bin/env python

import admin

import sys
import StringIO
from functools import partial
import time
import json
import os

public_ds = '../datastore/public'
private_ds = '../datastore/private'

class Args:
    pass

def pks_path(id):
    return os.path.join(public_ds, str(id), 'pks')

def tally_path(id):
    return os.path.join(private_ds, str(id), 'tally.tar.gz')

def results_public_path(id):
    return os.path.join(public_ds, str(id), 'results.json')

def tally_public_path(id):
    return os.path.join(public_ds, str(id), 'tally.tar.gz')

def capture(function):
    def wrapper(*args):
        stdout = sys.stdout
        output = StringIO.StringIO()
        sys.stdout = output
        function(args)
        sys.stdout = stdout
        value = output.getvalue().strip()
        return value

    return wrapper

@capture
def get_state(id):
    cfg = {}
    cfg['election_id'] = id
    args = Args()
    args.column = 'state'
    admin.show_column(cfg, args)

@capture
def count_votes(id):
    cfg = {}
    cfg['election_id'] = id
    args = Args()
    admin.count_votes(cfg, args)

def wait_for_state(id, state, seconds):
    def wait():
        s = get_state(id)
        print("wating for %s, got '%s'" % (state, s))
        return s in state

    wait_for(wait, seconds)

def wait_for(function, max):
    i = 0
    limit = max / 5
    while True:
        if function() == True:
            return 1
        else:
            if(i + 1 > limit):
                raise Exception("timeout")

            i += 1
            time.sleep(5)

def register(config):
    cfg = {}
    cfg['electionConfig'] = config
    args = Args()
    admin.register(cfg, args)

def create(id):
    cfg = {}
    cfg['election_id'] = id
    args = Args()
    admin.create(cfg, args)

def dump_pks(id):
    cfg = {}
    cfg['election_id'] = id
    args = Args()
    admin.dump_pks(cfg, args)
    if not os.path.isfile(pks_path(id)):
        raise Exception("pks not found")

def encrypt(id):
    cfg = {}
    cfg['election_id'] = id
    cfg['plaintexts'] = 'votes.json'
    cfg['encrypt-count'] = 0
    cfg['ciphertexts'] = 'ciphertexts_' + str(cfg['election_id'])

    args = Args()
    admin.encrypt(cfg, args)
    if not os.path.isfile(cfg['ciphertexts']):
        raise Exception("ciphertexts not found")

def start(id):
    cfg = {}
    cfg['election_id'] = id
    args = Args()
    admin.start(cfg, args)

def cast_votes(id):
    cfg['election_id'] = id
    cfg['ciphertexts'] = 'ciphertexts_' + str(cfg['election_id'])
    args = Args()
    before = count_votes(id)
    admin.cast_votes(cfg, args)
    after = count_votes(id)
    print("votes after cast: %s" % after)
    if not after > before:
        raise Exception("no votes were cast")

def tally(id):
    cfg = {}
    cfg['election_id'] = id
    args = Args()
    admin.tally(cfg, args)

def calculate_results(id):
    if not os.path.isfile(tally_path(id)):
        raise Exception("tally file not found (private ds)")
    cfg = {}
    cfg['election_id'] = id
    args = Args()
    args.results_config = 'config.json'
    admin.calculate_results(cfg, args)

def publish_results(id):
    cfg = {}
    cfg['election_id'] = id
    args = Args()
    admin.publish_results(cfg, args)
    if not os.path.isfile(results_public_path(id)):
        raise Exception("results file not found (public ds)")
    if not os.path.isfile(tally_public_path(id)):
        raise Exception("tally file not found (public ds")

jsonPath = 'base.json'
init_id = 69

with open(jsonPath, 'r') as f:
    cfg = json.loads(f.read())
cfg['id'] = init_id
print('************************ cfg ************************')
print(cfg)
print('*****************************************************')

register(cfg)
wait_for_state(cfg['id'], 'registered', 5)
create(cfg['id'])
wait_for_state(cfg['id'], 'created', 20)
dump_pks(cfg['id'])
encrypt(cfg['id'])
start(cfg['id'])
wait_for_state(cfg['id'], 'started', 5)
cast_votes(cfg['id'])
tally(cfg['id'])
wait_for_state(cfg['id'], ['tally_ok', 'results_ok'], 100)
calculate_results(cfg['id'])
wait_for_state(cfg['id'], 'results_ok', 5)
publish_results(cfg['id'])