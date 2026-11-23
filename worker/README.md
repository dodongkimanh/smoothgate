# Smoothgate account worker

A phone (Termux, Node 22+) that watches one Meta ad account through Smoothgate:

- every 30 minutes it reads the account's numbers, keeps them in `memory.db`, checks the rules (1tr / 0 SĐT, >350k/SĐT from 1.5tr, 4tr / 0 orders, <2tr/order means it can scale, CPM spikes, slow delivery, new orders) and posts new alerts;
- at 12:00 and 21:00 it sends today's numbers and its memory to Smoothgate, which asks Claude for a write-up.

Results show on the Smoothgate page **Máy theo dõi** (`/workers`). The worker only alerts; it never turns ads on or off. The Meta token and the Claude key stay on the server: the phone holds only its own worker key, and that key can read one account.

## Setup on a phone

```sh
mkdir -p ~/smoothgate-worker && cd ~/smoothgate-worker
# copy worker.mjs here, then create config.json (chmod 600):
# { "backend": "https://smoothgate-backend.kimanh-media.cloud", "key": "sgw_...", "device": "May 3" }
node worker.mjs --once            # one check now
pm2 start worker.mjs --name worker03 && pm2 save
```

The worker key comes from `POST /api/workers {name, adAccountId}` (admin login). It is shown only once; the server stores only its hash.
Options in config.json: `checkMinutes` (default 30) and `analysisSlots` (default `["12:00","21:00"]`).
