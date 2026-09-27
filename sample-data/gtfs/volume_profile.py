"""Estimate GTFS-realtime message volumes for one service day of a GTFS feed.

Usage: python3 volume_profile.py [feed.zip] [YYYY-MM-DD]
Assumptions: one VehiclePosition per active vehicle (by block) every 5 s,
one TripUpdate per running trip every 30 s plus one per stop arrival.
"""
import csv, io, zipfile, collections, datetime, sys, pathlib
Z=sys.argv[1] if len(sys.argv)>1 else str(pathlib.Path(__file__).with_name('metrotransit-mn-20260926.zip'))
z=zipfile.ZipFile(Z)
def rd(n): return csv.DictReader(io.TextIOWrapper(z.open(n),encoding='utf-8-sig'))
day=datetime.date.fromisoformat(sys.argv[2]) if len(sys.argv)>2 else datetime.date(2026,9,29)
dow=['monday','tuesday','wednesday','thursday','friday','saturday','sunday'][day.weekday()]
ds=day.strftime('%Y%m%d')
svc=set()
for r in rd('calendar.txt'):
    if r['start_date']<=ds<=r['end_date'] and r[dow]=='1': svc.add(r['service_id'])
for r in rd('calendar_dates.txt'):
    if r['date']==ds:
        (svc.add if r['exception_type']=='1' else svc.discard)(r['service_id'])
trips={r['trip_id']:r for r in rd('trips.txt') if r['service_id'] in svc}
def sec(t):
    h,m,s=map(int,t.split(':')); return h*3600+m*60+s
span={}; nstops=collections.Counter()
for r in rd('stop_times.txt'):
    t=r['trip_id']
    if t not in trips: continue
    nstops[t]+=1
    a=r['arrival_time'] or r['departure_time']
    if not a: continue
    s=sec(a); lo,hi=span.get(t,(s,s)); span[t]=(min(lo,s),max(hi,s))
blocks=collections.defaultdict(list)
for t,(lo,hi) in span.items(): blocks[trips[t]['block_id']].append((lo,hi))
# vehicle active from first trip start to last trip end of block
veh_sec=sum(max(h for _,h in v)-min(l for l,_ in v) for v in blocks.values())
trip_sec=sum(hi-lo for lo,hi in span.values())
ntrips=len(span); stops_total=sum(nstops[t] for t in span)
avg_stops=stops_total/ntrips
vp=veh_sec/5
tu_periodic=trip_sec/30
tu_arrival=stops_total  # one TU per stop arrival
tu=tu_periodic+tu_arrival
# rows written per TU: stops passed since last update (~1-2) + min(lookahead, remaining)
LOOKAHEAD=10
tu_row_upserts=tu*(avg_stops/2+1)
tu_row_upserts_la=tu*(LOOKAHEAD+2)
print(f"day={day} trips={ntrips} blocks={len(blocks)} avg_stops={avg_stops:.1f} stop_times={stops_total}")
print(f"vehicle-hours={veh_sec/3600:.0f} avg_active_vehicles_24h={veh_sec/86400:.0f}")
print(f"VP msgs/day={vp:,.0f}")
print(f"TU msgs/day periodic={tu_periodic:,.0f} arrival={tu_arrival:,.0f} total={tu:,.0f}")
print(f"TU row upserts/day all-stops={tu_row_upserts:,.0f} lookahead{LOOKAHEAD}={tu_row_upserts_la:,.0f}; distinct TU rows/day={stops_total:,}")
# peak minute
cnt=collections.Counter(); tcnt=collections.Counter()
for v in blocks.values():
    lo=min(l for l,_ in v)//60; hi=max(h for _,h in v)//60
    for m in range(lo,hi+1): cnt[m]+=1
for lo,hi in span.values():
    for m in range(lo//60,hi//60+1): tcnt[m]+=1
pm=max(cnt,key=cnt.get); print(f"peak vehicles={cnt[pm]} at {pm//60:02d}:{pm%60:02d}; peak trips={max(tcnt.values())}")
peak_trips=max(tcnt.values())
# arrivals per second at peak: stop events in peak hour
ev=collections.Counter()
for r in rd('stop_times.txt'):
    if r['trip_id'] in trips and r['arrival_time']:
        ev[sec(r['arrival_time'])//3600]+=1
ph=max(ev,key=ev.get); print(f"peak hour {ph}: stop arrivals/s={ev[ph]/3600:.1f}")
print(f"peak msg/s: VP={cnt[pm]/5:.1f} TUperiodic={peak_trips/30:.1f} TUarrival={ev[ph]/3600:.1f} total={cnt[pm]/5+peak_trips/30+ev[ph]/3600:.1f}")
print(f"peak TU row upserts/s all-stops~{(peak_trips/30+ev[ph]/3600)*(avg_stops/2+1):.0f} lookahead{LOOKAHEAD}~{(peak_trips/30+ev[ph]/3600)*(LOOKAHEAD+2):.0f}")
