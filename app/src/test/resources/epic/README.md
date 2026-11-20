# Epic weekly fixtures

`weekly.json` is the synthetic `epicFeed(now)` fixture from Free-Games-Watcher revision `9e302be8c133f06332433c8bd68eed8d3ec79c78`, `tests/multistore-fixtures.mjs`. Time is fixed at `2026-11-19T12:00:00Z` (`1795089600000`). The source uses the MIT license reproduced in `LICENSE`.

The extension parser `src/lib/providers/epicProvider.js`, `parseEpicFeed`, returns current offer `epic:test-namespace:123`, future offer `epic:test-namespace:456`, and `complete=true`. The Android test `frozenExtensionExampleIncludesCurrentAndExcludesFuture` checks the current feed and exact dates. Upcoming promotions stay outside the first-stage feed.

`permanently-free.json` changes the first offer's original price to zero. Both parsers return zero current offers with `complete=true`. The Android test is `permanentlyFreeNegativeExampleMatchesExtension`.

Parity was executed against the local extension source on 2026-11-19. Other JVM tests cover expired and demo offers, country restrictions, incomplete records, duplicate windows, and unsafe product URLs.
