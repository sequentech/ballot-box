import geoip2.database
import geoip2.webservice
from geoip2.errors import AddressNotFoundError
import codecs
import json
import pickle
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
    radius = 63711000 # earth radius in meters

    dlat = radians(lat2-lat1)
    dlon = radians(lon2-lon1)
    a = (sin(dlat/2) * sin(dlat/2) + cos(radians(lat1)) * cos(radians(lat2)) *
         sin(dlon/2) * sin(dlon/2))
    c = 2 * atan2(sqrt(a), sqrt(1-a))
    d = radius * c

    return d

class CityCountryNamesIpFilter(object):
    '''
    Filter by city and country name
    '''
    data = None

    def __init__(self, data):
        self.data = data

    def check(self, record):
        return self.data['city_name'] == record.city.name and\
            self.data['country_name'] == record.country.name

class LocationRadiusIpFilter(object):
    '''
    Filter by a lat+long plus a radius in meters
    '''
    data = None

    def __init__(self, data):
        self.data = data

    def check(self, record):
        return calc_distance(
            (self.data['location_latitude'], self.data['location_longitude']),
            (record.location.latitude, record.location.longitude)
        ) < self.data['radius_meters']

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
    location_filters = []

    # dictionary where the key is the ip address, and the value contains another
    # dictionary with keys like "voter_id", "election_id" or "geolocation"
    voter_ips = dict()

    election_counts = dict()
    num_lookups = 0

    def __init__(self, filter_config):
        '''
        Loads filter configuration
        '''
        global LOG_LEVEL

        if 'log_level' in filter_config:
            LOG_LEVEL = LOG_LEVELS[filter_config['log_level']]

        if 'geoip_db_path' in filter_config:
            self.gi = geoip2.database.Reader(filter_config['geoip_db_path'])
        else:
            self.gi = geoip2.webservice.Client(
                filter_config['geoip_userid'], filter_config['geoip_license_key'])

        if 'geoip_cache' in filter_config and\
            os.access(filter_config['geoip_cache'], os.R_OK):
            with open(filter_config['geoip_cache'], mode='r') as f:
                self.cache = pickle.load(f)


        for location_check in filter_config['locations_whitelist']:
            if location_check['filter_type'] == 'city_country_names':
                obj_filter = CityCountryNamesIpFilter(location_check)
            else:
                obj_filter = LocationRadiusIpFilter(location_check)
            self.location_filters.append(obj_filter)

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

        if LOG_LEVEL >= LOG_LEVEL_INFO:
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
        except Exception as e:
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
            if LOG_LEVEL >= LOG_LEVEL_DEBUG:
                print("ip for voter_id %s not found, filtering" % vote.voter_id)
            return False
        record = ip['geolocation']
        found_location = False
        for location_filter in self.location_filters:
            if not record:
                if LOG_LEVEL >= LOG_LEVEL_DEBUG:
                    print("ip = '%s' no record found, filtering" % (ip['ip']))
                return False
            found_location = found_location or location_filter.check(record)

        if not found_location and LOG_LEVEL >= LOG_LEVEL_DEBUG:
            print(("filtered: ip = '%s' voter_id = '%s' city_name = %s"
                ", country_code = '%s'" % (ip['ip'], vote.voter_id,
                record.city.name, record.country.name)).encode('ascii', 'ignore'))
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
