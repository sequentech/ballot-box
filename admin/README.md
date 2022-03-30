# Filtering

Example of voter ids filtering:

    wget http://geolite.maxmind.com/download/geoip/database/GeoLite2-City.mmdb.gz
    gunzip GeoLite2-City.mmdb.gz
    python admin.py dump_ids 101000 --filter-config /home/agoraelections/ballot-box/admin/filter_example.json

log generation:

    python admin.py dump_ids ~/agora-tools/config/proceso-primarias-4-ids.txt --filter-config filter_example.json  > ~/filtered.log

list elections:

    grep "election " ~/filtered.log
    election 103000: 564 votes (96.08% from 587 total)
    election 103030: 430 votes (92.67% from 464 total)
    election 103020: 610 votes (95.46% from 639 total)
    election 103080: 416 votes (95.19% from 437 total)
    election 103070: 315 votes (96.04% from 328 total)
    election 103060: 484 votes (96.03% from 504 total)
    election 102001: 3385 votes (95.35% from 3550 total)
    election 103010: 866 votes (97.85% from 885 total)
    election 103050: 182 votes (93.33% from 195 total)
    election 103040: 73 votes (93.59% from 78 total)
    election 101000: 3693 votes (95.72% from 3858 total)
    election 103090: 277 votes (97.54% from 284 total)

count filtered ips:

     grep "filtered,ip" ~/filtered.log | cut -f 3 -d, | sort | uniq | wc -l
     163

count not-located ips:

    grep "filtered,ip" ~/filtered.log | grep "city_name,None,country,None" | wc -l
    8


