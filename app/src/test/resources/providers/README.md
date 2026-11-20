# Store provider fixtures

These reduced public responses are copied from Free-Games-Watcher revision
9e302be8c133f06332433c8bd68eed8d3ec79c78 under its MIT license, reproduced in LICENSE.

- epic-mafia-hotel.json and egdata-mafia-hotel.json describe the same Epic offer.
  EpicGogProviderTest fixes time at 2026-10-27T12:00:00Z and checks identity, URL,
  expiration, stale mirror prices, coupons and region restrictions.
- gog-a-new-beginning.json is the anonymous homepage section observed on
  2026-10-31. The test verifies the homepage claim address and the deadline
  2026-11-02T14:00:59Z, including the price-independent giveaway terms.

The negative inputs are small mutations in EpicGogProviderTest. Future/expired
promotions and zero-price catalog records without giveaway terms produce no
active cards. Transport tests cover fallback, partial second-page failure and
Retry-After. Live responses are kept outside the repository.

EGData now redirects POST searches with HTTP 303 to the canonical GET endpoint.
The Android fallback uses that GET endpoint directly with explicit country,
page, limit, onSale and price ordering parameters.
