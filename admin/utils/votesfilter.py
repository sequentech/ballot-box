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
import geoip2.database
import geoip2.webservice
from geoip2.errors import AddressNotFoundError
import codecs
import json
import pickle
import requests
import re
import os

LOG_LEVEL_ERROR = 0
LOG_LEVEL_INFO =  1
LOG_LEVEL_DEBUG = 2
LOG_LEVELS = {
  "error": 0,
  "info":  1,
  "debug": 2
}

# current log level
LOG_LEVEL = LOG_LEVEL_INFO

# source: http://stackoverflow.com/questions/17265278/distance-calculation-in-python-with-google-earth-coordinates
# adapted from haversine.py <https://gist.github.com/rochacbruno/2883505>
# see also <http://en.wikipedia.org/wiki/Haversine_formula>
from math import atan2, cos, sin, sqrt, radians
def calc_distance(origin, destination):
    """great-circle distance between two points on a sphere
       from their longitudes and latitudes in meters"""
    lat1, lon1 = origin
    lat2, lon2 = destination
    radius = 6371100 # earth radius in meters

    dlat = radians(lat2-lat1)
    dlon = radians(lon2-lon1)
    a = (sin(dlat/2) * sin(dlat/2) + cos(radians(lat1)) * cos(radians(lat2)) *
         sin(dlon/2) * sin(dlon/2))
    c = 2 * atan2(sqrt(a), sqrt(1-a))
    d = radius * c

    return d

class BaseIpFilterOption(object):
    data = None

    def __init__(self, data):
        self.data = data

    def check(self, record, geoservices):
        return False


class CityCountryNamesIpFilter(BaseIpFilterOption):
    '''
    Filter by city and country name
    '''
    def __init__(self, data):
        self.data = data

    def check(self, record, geoservices):
        if "num_geoservices_tested" in self.data and\
            self.data['num_geoservices_tested'] != len(geoservices):
            return False

        return self.data['city_name'] == record['city_name'] and\
            self.data['country_name'] == record['country_name']


class LocationRadiusIpFilter(BaseIpFilterOption):
    '''
    Filter by a lat+long plus a radius in meters
    '''
    def __init__(self, data):
        self.data = data

    def check(self, record, geoservices):
        return calc_distance(
            (self.data['location_latitude'], self.data['location_longitude']),
            (record['latitude'], record['longitude'])
        ) < self.data['radius_meters']


class GeoipService(object):
    '''
    Ip geolocation service
    '''
    name = 'dummy'

    data = None

    cache_misses = 0
    cache_hits = 0

    local_service_cache = {}

    def __init__(self, data, cache):
        self.base_init(data, cache)

    def __del__(self):
        print("dummy, cache misses = %d, hits = %d" % (self.cache_misses, self.cache_hits))

    def get_name(self):
        return self.name

    def get_cache_key(self):
        return self.data.get('cache_key', None)

    def get_cache(self):
        return self.local_service_cache

    def base_init(self, data, cache):
        self.data = data
        if data.get('cache_key', None) is not None:
            self.local_service_cache = cache.get(data['cache_key'], {})
        else:
            self.local_service_cache = {}

    def get_not_located_policy(self):
        return self.data['not-located-policy']

    def get_filtered_policy(self):
        return self.data['filtered-policy']

    def _get_cached(self, ip):
        if ip in self.local_service_cache:
            self.cache_hits += 1
            return self.local_service_cache[ip]
        else:
            return None

    def _set_cached(self, ip, data):
        self.local_service_cache[ip] = data

    def get_record(self, ip):
        '''
        A record, if found, should have the following format/data:
        {
            'city_name': 'Foo',
            'country_name': 'Foo',
            'latitude': 44.11411,
            'longitude': 44.11411
        }

        If not found, returns None. Does not cache requests.
        '''
        cached = self._get_cached(ip)
        if cached is not None:
            return cached

        ret = None
        self._set_cached(ip, ret)
        return ret


class MaxMindService(GeoipService):
    '''
    Geolocalization ip service that uses max mind database or API for
    geolocation
    '''
    name = 'maxmind'

    gi = None

    service_type = None

    def __init__(self, data, cache):
        self.base_init(data, cache)

        if 'geoip_db_path' in data:
            self.service_type = "db"
            self.gi = geoip2.database.Reader(data['geoip_db_path'])
        else:
            self.service_type = "api"
            self.gi = geoip2.webservice.Client(
                data['geoip_userid'], data['geoip_license_key'])

    def __del__(self):
        print("maxmind(%s), cache misses = %d, hits = %d" % (self.service_type,
            self.cache_misses, self.cache_hits))

    def get_name(self):
        return "maxmind(%s)" % self.service_type

    def get_record(self, ip):
        '''
        A record, if found, should have the following format/data:
        {
            'city_name': 'Foo',
            'country_name': 'Foo',
            'latitude': 44.11411,
            'longitude': 44.11411
        }

        If not found, returns None. Does not cache requests.
        '''
        cached = self._get_cached(ip)
        if cached is not None:
            return cached

        ret = None
        try:
            if LOG_LEVEL >= LOG_LEVEL_DEBUG:
                print("maxmind(%s): locating ip '%s'" % (self.service_type, ip))
            record = self.gi.city(ip)
            if record:
                ret = {
                    'city_name': record.city.name,
                    'country_name': record.country.name,
                    'latitude': record.location.latitude,
                    'longitude': record.location.longitude
                }
        except Exception as e:
            pass

        self._set_cached(ip, ret)
        return ret


class IpApiService(GeoipService):
    '''
    Geolocalization ip service that uses max mind database or API for
    geolocation
    '''
    name = 'ipapi'

    def __init__(self, data, cache):
        self.base_init(data, cache)

    def __del__(self):
        print("ipapi, cache misses = %d, hits = %d" % (self.cache_misses, self.cache_hits))

    def get_record(self, ip):
        '''
        A record, if found, should have the following format/data:
        {
            'city_name': 'Foo',
            'country_name': 'Foo',
            'latitude': 44.11411,
            'longitude': 44.11411
        }

        If not found, returns None. Does not cache requests.
        '''
        cached = self._get_cached(ip)
        if cached is not None:
            return cached

        ret = None
        try:
            if LOG_LEVEL >= LOG_LEVEL_DEBUG:
                print("ipapi: locating ip '%s'" % ip)
            req = requests.get("http://ip-api.com/json/%s" % ip)
            if req.status_code != 200:
                return None
            json = req.json()
            if json['city']:
                ret = {
                    'city_name': json['city'],
                    'country_name': json['country'],
                    'latitude': json['lat'],
                    'longitude': json['lon']
                }
        except Exception as e:
            pass

        self._set_cached(ip, ret)
        return ret


class IpFilter(object):
    '''
    Filters votes based on geolocation data
    '''
    geo_services = None

    # list of filters of valid locations read from the config file
    location_filters = []

    # dictionary where the key is the ip address, and the value contains another
    # dictionary with keys like "voter_id", "election_id" or "geolocation"
    voter_ips = dict()

    election_counts = dict()

    filter_config = {}

    def __init__(self, filter_config):
        '''
        Loads filter configuration
        '''
        global LOG_LEVEL

        self.filter_config = filter_config

        if 'log_level' in filter_config:
            LOG_LEVEL = LOG_LEVELS[filter_config['log_level']]

        cache = {}
        if 'geoip_cache' in filter_config and\
            os.access(filter_config['geoip_cache'], os.R_OK):
            with open(filter_config['geoip_cache'], mode='r') as f:
                cache = pickle.load(f)

        # add geoservices
        self.geo_services = []
        for geoservice in filter_config['geolocation_services']:
            if geoservice['type'] == 'maxmind':
                obj = MaxMindService(geoservice, cache)
            elif geoservice['type'] == 'ipapi':
                obj = IpApiService(geoservice, cache)
            elif geoservice['type'] == 'dummy':
                obj = GeoipService(geoservice, cache)
            else:
                raise Exception()
            self.geo_services.append(obj)

        for location_check in filter_config['locations_whitelist']:
            if location_check['type'] == 'city_country_names':
                obj_filter = CityCountryNamesIpFilter(location_check)
            elif location_check['type'] == 'location_radius':
                obj_filter = LocationRadiusIpFilter(location_check)
            else:
                raise Exception()
            self.location_filters.append(obj_filter)

        # load voter_ips relations
        prog = re.compile(filter_config['ips_regex'])
        with open(filter_config['ips_log'], mode='r') as f:
            for line in f:
                res = prog.match(line)
                if res is not None:
                    self.voter_ips[res.group('voter_id')] = {
                        'election_id': res.group('election_id'),
                        'ip': res.group('ip')
                    }
                    self.add_count(str(res.group('election_id')), 'total')

    def add_count(self, elid, key):
        if elid not in self.election_counts:
            self.election_counts[elid] = dict(
                total=0,
                total_filtered=0,
                ip_not_geolocated=0,
                ip_not_recorded=0,
                election_id=elid)

        self.election_counts[elid][key] += 1

    def __del__(self):

        # save cache
        cache = {}
        if 'geoip_cache' in self.filter_config:
            with open(self.filter_config['geoip_cache'], mode='w') as f:
                for geoservice in self.geo_services:
                    key = geoservice.get_cache_key()
                    if key:
                      cache[key] = geoservice.get_cache()
                pickle.dump(cache, f)

        if LOG_LEVEL >= LOG_LEVEL_INFO:
            for key, val in self.election_counts.items():
                print(json.dumps(val, sort_keys=True))

    def get_location(self, ip, geoservices=None):
        '''
        Geolocates an ip address
        '''
        if geoservices is None:
            geoservices = self.geo_services

        ret = None
        for geoservice in geoservices:
            record = geoservice.get_record(ip['ip'])
            if not record:
                continue

            # if we found a record with no city, we try to get a new record
            # and if the new record gets a city name it's used, but if it
            # does not find any other record in the next db, the previous
            # one will be used
            elif record['city_name'] in ['', None] and not ret:
              ret = record
              continue

            ret = record
            break

        return ret

    def check(self, vote, election_id):
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
            self.add_count(str(election_id), 'ip_not_recorded')
            if LOG_LEVEL >= LOG_LEVEL_DEBUG:
                print("ip for voter_id %s not found, filtering" % vote.voter_id)
            return False

        def check(self, ip, geoservices, first=None):
            record = self.get_location(ip, geoservices)
            if first == None:
              first = record

            if not record and geoservices[0].get_not_located_policy() == 'accept':
                if LOG_LEVEL >= LOG_LEVEL_DEBUG:
                  print("accepting not found ip = '%s' voter_id = '%s'" % (
                      ip['ip'], vote.voter_id))
                self.add_count(ip['election_id'], 'ip_not_geolocated')
                return True, None, first

            found_filter = False
            for location_filter in self.location_filters:
                if not record:
                    if LOG_LEVEL >= LOG_LEVEL_DEBUG:
                        print("ip = '%s' no record found, filtering" % (ip['ip']))
                    return False, None, first
                found_filter = found_filter or location_filter.check(record, geoservices)

            if found_filter or len(geoservices) <= 1 or\
                geoservices[0].get_filtered_policy() != 'relocate':
                return found_filter, record, first
            else:
                if LOG_LEVEL >= LOG_LEVEL_DEBUG:
                    print("trying to relocate with service '%s'" %
                        geoservices[1].get_name())
                return check(self, ip, geoservices[1:], first)

        found_filter, record, first = check(self, ip, self.geo_services)
        if found_filter:
            self.add_count(ip['election_id'], 'total_filtered')

        def get_attr(record, attr):
          if record is None:
              return ''
          else:
              return record.get(attr, '')

        if not found_filter:
          if LOG_LEVEL >= LOG_LEVEL_DEBUG:
            print(("filtered,ip,%s,voter_id,%s,city_name,%s,country,%s" % (
                ip['ip'],
                vote.voter_id,
                get_attr(first, "city_name"),
                get_attr(first, "country_name"))).encode('ascii', 'ignore'))
        return found_filter


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

    def check(self, voter, election_id):
        for f in self.filters:
            if not f.check(voter, election_id):
                return False
        return True
