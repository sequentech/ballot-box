import geoip2.database
import geoip2.webservice
from geoip2.errors import AddressNotFoundError
import codecs
import json
import pickle
import re
import os

class IpFilter(object):
    '''
    Filters votes based on geolocation data
    '''

    # gi is the geoip2 instance
    gi = None

    # contains a list of cached ips resolutions. useful when using a service
    # where you have to pay for each resolution, so that you don't pay more
    # than it's needed
    cache = dict()

    # list of filters of valid locations read from the config file
    locations_whitelist = []

    # dictionary where the key is the ip address, and the value contains another
    # dictionary with keys like "voter_id", "election_id" or "geolocation"
    voter_ips = dict()

    election_counts = dict()
    num_lookups = 0

    def __init__(self, filter_config):
        '''
        Loads filter configuration
        '''

        if 'geoip_db_path' in filter_config:
            self.gi = geoip2.database.Reader(filter_config['geoip_db_path'])
        else:
            self.gi = geoip2.webservice.Client(
                filter_config['geoip_userid'], filter_config['geoip_license_key'])

        if 'geoip_cache' in filter_config and\
            os.access(filter_config['geoip_cache'], os.R_OK):
            with open(filter_config['geoip_cache'], mode='r') as f:
                self.cache = pickle.load(f)

        self.locations_whitelist = filter_config['locations_whitelist']

        # load voter_ips relations
        prog = re.compile(filter_config['ips_regex'])
        with open(filter_config['ips_log'], mode='r') as f:
            for line in f:
                res = prog.match(line)
                if res is not None:
                    self.voter_ips[res.group('voter_id')] = {
                        'election_id': res.group('election_id'),
                        'ip': res.group('ip'),
                        'geolocation': self.get_location(res.group('ip'))
                    }
                    elid = res.group('election_id')
                    if elid not in self.election_counts:
                        self.election_counts[str(elid)] = 1
                    else:
                        self.election_counts[str(elid)] += 1

        print("num_lookups = %d" % self.num_lookups)
        for elid in self.election_counts.keys():
            print("election id=%s total_votes=%d" % (elid, self.election_counts[elid]))

        # save cache
        if 'geoip_cache' in filter_config:
            with open(filter_config['geoip_cache'], mode='w') as f:
                pickle.dump(self.cache, f)

    def get_location(self, ip):
        '''
        Geolocates an ip address
        '''

        if ip in self.cache:
            return self.cache[ip]

        try:
          self.num_lookups += 1
          ret = self.cache[ip] = self.gi.city(ip)
          return ret
        except:
          return None

    def check(self, vote):
        '''
        For any given vote, check the location_whitelist and see if it's a
        match. This function returns True if the vote is considered valid based
        on those filters, or False otherwise.

        NOTE: Geolocation data is not perfect. There will be some false
        negatives and some false positives. There will also be some ip addresses
        that the geolocation database won't be able to geolocate, in which case
        this function will err on the side of caution and return False.
        '''
        ip = self.voter_ips.get(vote.voter_id, None)
        if ip is None:
            print("ip for voter_id %s not found, filtering" % vote.voter_id)
            return False
        record = ip['geolocation']
        found_location = False
        for location_checks in self.locations_whitelist:
            if not record:
                print("ip = '%s' no record found, filtering" % (ip['ip']))
                return False
            found_location_element = True

            for key, value in location_checks.items():
                if key == 'city_name' and record.city.name != value:
                    found_location_element = False
                elif key == 'country_name' and record.country.name != value:
                    found_location_element = False

            if found_location_element:
                found_location = True

        if not found_location:
            print(("filtered: ip = '%s' voter_id = '%s' city_name = %s, country_code = '%s'" % (ip['ip'], vote.voter_id, record.city.name, record.country.name)).encode('ascii', 'ignore'))
        return found_location

class VotesFilter(object):
    config = []
    filters = []
    available_filters = {
        "ip": IpFilter
    }

    def __init__(self, filter_config_path):
        # load config
        with codecs.open(filter_config_path, encoding='utf-8', mode='r') as f:
            data = f.read()
            self.config = json.loads(data)
        # load filters based on config
        self._load_filters()

    def _load_filters(self):
        self.filters = [
            self.available_filters[f['type']](f)
            for f  in self.config]

    def check(self, voter):
        for f in self.filters:
            if not f.check(voter):
                return False
        return True
