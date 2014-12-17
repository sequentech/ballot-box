#!/usr/bin/env python

import admin

import sys
import StringIO
from functools import partial
import time
import json
import os

import argparse
from argparse import RawTextHelpFormatter

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
    print('> register..')
    admin.register(cfg, args)

def create(id):
    cfg = {}
    cfg['election_id'] = id
    args = Args()
    print('> create..')
    admin.create(cfg, args)

def dump_pks(id):
    cfg = {}
    cfg['election_id'] = id
    args = Args()
    print('> dump pks..')
    admin.dump_pks(cfg, args)
    if not os.path.isfile(pks_path(id)):
        raise Exception('pks not found')

def encrypt(id, encrypt_count):
    cfg = {}
    cfg['election_id'] = id
    cfg['plaintexts'] = 'votes.json'
    cfg['encrypt-count'] = encrypt_count
    cfg['ciphertexts'] = 'ciphertexts_' + str(cfg['election_id'])

    args = Args()
    print('> encrypt..')
    admin.encrypt(cfg, args)
    if not os.path.isfile(cfg['ciphertexts']):
        raise Exception('ciphertexts not found')

def start(id):
    cfg = {}
    cfg['election_id'] = id
    args = Args()
    print('> start..')
    admin.start(cfg, args)

def cast_votes(id):
    cfg = {}
    cfg['election_id'] = id
    cfg['ciphertexts'] = 'ciphertexts_' + str(cfg['election_id'])
    args = Args()
    before = count_votes(id)
    print('> cast_votes..')
    admin.cast_votes(cfg, args)
    after = count_votes(id)
    print('votes after casting: %s' % after)
    if not after > before:
        raise Exception('no votes were cast')

def tally(id):
    cfg = {}
    cfg['election_id'] = id
    args = Args()
    print('> tally..')
    admin.tally(cfg, args)

def calculate_results(id, results_config):
    if not os.path.isfile(tally_path(id)):
        raise Exception('tally file not found (private ds)')
    cfg = {}
    cfg['election_id'] = id
    args = Args()
    args.results_config = results_config
    print('> calculate_results..')
    admin.calculate_results(cfg, args)

def publish_results(id):
    cfg = {}
    cfg['election_id'] = id
    args = Args()
    print('> publish_results..')
    admin.publish_results(cfg, args)
    if not os.path.isfile(results_public_path(id)):
        raise Exception('results file not found (public ds)')
    if not os.path.isfile(tally_public_path(id)):
        raise Exception('tally file not found (public ds')

def serial(cfg, args):

    print('>>> starting serial run')

    for i in range(0, args.total_cycles):
        cfg['id'] = args.init_id + i
        print('>> starting cycle id = %d' % cfg['id'])
        register(cfg)
        wait_for_state(cfg['id'], 'registered', 5)
        create(cfg['id'])
        wait_for_state(cfg['id'], 'created', 20)
        dump_pks(cfg['id'])
        encrypt(cfg['id'], args.encrypt_count)
        start(cfg['id'])
        wait_for_state(cfg['id'], 'started', 5)
        cast_votes(cfg['id'])
        tally(cfg['id'])
        wait_for_state(cfg['id'], ['tally_ok', 'results_ok'], 100)
        calculate_results(cfg['id'], args.results_config)
        wait_for_state(cfg['id'], 'results_ok', 5)
        publish_results(cfg['id'])

    print('>>> finished serial run (last id = %d)' % cfg['id'])

def parallel(cfg, args):

    print('>>> starting parallel run')

    for i in range(0, args.total_cycles):
        cfg['id'] = args.init_id + i
        print('>> create, id = %d' % cfg['id'])
        register(cfg)
        wait_for_state(cfg['id'], 'registered', 5)
        create(cfg['id'])
        wait_for_state(cfg['id'], 'created', 20)

    for i in range(0, args.total_cycles):
        cfg['id'] = args.init_id + i
        print('>> vote, id = %d' % cfg['id'])
        dump_pks(cfg['id'])
        encrypt(cfg['id'], args.encrypt_count)
        start(cfg['id'])
        wait_for_state(cfg['id'], 'started', 5)
        cast_votes(cfg['id'])

    for i in range(0, args.total_cycles):
        cfg['id'] = args.init_id + i
        print('>> tally + publish, id = %d' % cfg['id'])
        tally(cfg['id'])
        wait_for_state(cfg['id'], ['tally_ok', 'results_ok'], 100)
        calculate_results(cfg['id'], args.results_config)
        wait_for_state(cfg['id'], 'results_ok', 5)
        publish_results(cfg['id'])

    print('>>> finished parallel run (last id = %d)' % cfg['id'])

def main(argv):
    parser = argparse.ArgumentParser(description='cycle testing script', formatter_class=RawTextHelpFormatter)
    parser.add_argument('-e', '--encrypt-count', help='number of votes to encrypt (generates duplicates if more than in json file)', type=int, default = 0)
    parser.add_argument('-c', '--election-config', help='config file for election', default='election.json')
    parser.add_argument('-r', '--results-config', help='config file for agora-results', default='config.json')
    parser.add_argument('-i', '--init-id', help='config file for agora-results', type=int, required=True)
    parser.add_argument('-t', '--total-cycles', help='config file for agora-results', type=int, default='1')
    parser.add_argument('-p', '--parallel', help='config file for agora-results', action='store_true')
    args = parser.parse_args()

    print('************************ cfg ************************')
    print('election_config = %s' % args.election_config)
    print('results_config = %s' % args.results_config)
    print('init_id = %d' % args.init_id)
    print('encrypt_count = %d' % args.encrypt_count)
    print('total_cycles = %d' % args.total_cycles)
    print('parallel = %s' % args.parallel)

    if not os.path.isfile(args.election_config):
        raise Exception("election config not found '%s'" % args.election_config)

    if not os.path.isfile(args.results_config):
        raise Exception("results config not found '%s'" % args.results_config)

    with open(args.election_config, 'r') as f:
        cfg = json.loads(f.read())
    cfg['id'] = args.init_id

    print(cfg)
    print('*****************************************************')

    if args.parallel:
        parallel(cfg, args)
    else:
        serial(cfg, args)

if __name__ == "__main__":
    main(sys.argv[1:])
