# Filtering

Example of voter ids filtering:

    wget http://geolite.maxmind.com/download/geoip/database/GeoLite2-City.mmdb.gz
    gunzip GeoLite2-City.mmdb.gz
    python admin.py dump_ids 101000 --filter-config /home/agoraelections/agora-elections/admin/filter_example.json

